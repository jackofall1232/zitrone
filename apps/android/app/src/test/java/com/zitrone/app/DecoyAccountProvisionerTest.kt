// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.DecoyState
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import com.zitrone.app.crypto.vault.openPayload
import com.zitrone.app.decoy.DecoyAccountProvisioner
import com.zitrone.app.decoy.DecoyIdentity
import com.zitrone.app.decoy.DecoyPowSolver
import com.zitrone.app.decoy.DecoyRelayApi
import com.zitrone.app.net.ApiClient
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

/**
 * [DecoyAccountProvisioner] — the ordering rule, the crash matrix, the shared-resource discipline,
 * and the silent-degradation contract.
 *
 * **The invariant every scenario re-asserts** is that the vault never ends up referencing a
 * synthetic account whose identity key was not persisted with it. An interruption may leave an
 * ORPHANED relay account (harmless); it may never leave a dangling reference (permanent —
 * unauthenticatable, undeletable, and fatal to every subsequent decoy).
 *
 * The AEAD + DEFLATE + TLV byte path is real (a commit genuinely encodes and reseals); the relay
 * and the proof-of-work solver are faked so failure points are placed deterministically.
 */
class DecoyAccountProvisionerTest {

    private val ops = LibsodiumVaultOps(SodiumJava())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun runtimeOf(state: VaultState = VaultState.empty()): VaultRuntime = Vault(state).runtime

    /**
     * A live vault whose DURABLE writes are observable.
     *
     * `VaultRuntime.mutate` only schedules a reseal, so every assertion about surviving a crash
     * reads the sealed region the persist sink was handed — opened with the vault key and decoded
     * through the real codec — not the live `VaultState`. The 60 s cooldown means the background
     * ceiling never fires here: anything on "disk" was flushed deliberately.
     */
    private inner class Vault(
        state: VaultState = VaultState.empty(),
        /**
         * Zero makes the coalescing ceiling fire on EVERY mutation instead of batching — with an
         * unconfined flush context that turns "the background reseal happened to land between two
         * mutations" from a rare race into a deterministic interleaving. That is the only way a
         * multi-step commit's intermediate state ever reaches disk, so it is exactly the fault a
         * one-mutate claim has to survive.
         */
        cooldownMs: Long = 60_000L,
        flushContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
    ) {

        /** Our own copy — [VaultSession] wipes the key it is constructed with. */
        val vaultKey = ByteArray(VAULT_KEY_BYTES) { 0x11 }

        /** EVERY sealed region the sink was handed, oldest first. */
        val generations = mutableListOf<ByteArray>()

        /** The last sealed region the sink was handed, or null when nothing was ever persisted. */
        val lastSealed: ByteArray? get() = generations.lastOrNull()

        private val session = VaultSession(
            scope = scope,
            ops = ops,
            initialPayload = VaultStateCodec.encode(state),
            initialVaultKey = vaultKey.copyOf(),
            slotIndex = 0,
            persist = { _, sealed -> generations += sealed.copyOf() },
            cooldownMs = cooldownMs,
            flushContext = flushContext,
        )

        val runtime = VaultRuntime(session, state)

        /** The whole state as it exists ON DISK, or null when nothing was ever persisted. */
        fun durableState(): VaultState? {
            val sealed = lastSealed ?: return null
            return VaultStateCodec.decode(requireNotNull(openPayload(vaultKey, sealed, ops)))
        }

        /** The decoy section as it exists ON DISK. */
        fun durableDecoy(): DecoyState? = durableState()?.decoy

        /** The decoy section of EVERY generation ever written. */
        fun everyDurableDecoy(): List<DecoyState?> = generations.map {
            VaultStateCodec.decode(requireNotNull(openPayload(vaultKey, it, ops))).decoy
        }

        /** Force whatever is merely SCHEDULED out to the sink, ignoring a capacity refusal. */
        fun forceFlush() = session.flushNow()
    }

    /**
     * The on-disk twin of [assertNoDanglingReference]: no persisted generation may ever carry an
     * account id without its identity key. This is what a two-step commit would break — the live
     * state would look whole while the image the next session opens carried only the id.
     */
    private fun assertNoDanglingReferenceOnDisk(vault: Vault) {
        val decoy = vault.durableDecoy() ?: return
        if (decoy.accountId != null) {
            assertNotNull("a PERSISTED account id without its identity key — dangling reference", decoy.identityKeyPair)
        }
        if (decoy.identityKeyPair != null) {
            assertNotNull("a PERSISTED identity key without its account id", decoy.accountId)
        }
    }

    /**
     * THE assertion this suite exists for. Called after every scenario, successful or not: an
     * account id and its identity keypair are committed together or not at all.
     */
    private fun assertNoDanglingReference(runtime: VaultRuntime) {
        runtime.read { state ->
            val decoy = state.decoy ?: return@read
            if (decoy.accountId != null) {
                assertNotNull(
                    "an account id is present WITHOUT its identity key — dangling reference",
                    decoy.identityKeyPair,
                )
            }
            if (decoy.identityKeyPair != null) {
                assertNotNull(
                    "an identity key is present WITHOUT its account id",
                    decoy.accountId,
                )
            }
        }
    }

    private fun provisioner(
        runtime: VaultRuntime,
        relay: DecoyRelayApi,
        now: () -> Long = { FIXED_NOW },
        random: Random = Random(7L),
    ) = DecoyAccountProvisioner(
        runtime = runtime,
        relay = relay,
        powSolver = FakeSolver(),
        clock = now,
        random = random,
    )

    // ── the happy path, and the ordering it must obey ─────────────────────────────

    @Test
    fun `provisioning registers on the relay BEFORE it commits, and the commit is DURABLE`() {
        val vault = Vault()
        // The fake reads the vault at the moment the relay call lands, so "register precedes
        // commit" is observed rather than inferred from the code's shape.
        val relay = FakeRelay(observeAtRegister = { vault.runtime.read { it.decoy } })
        val provisioner = provisioner(vault.runtime, relay)

        assertTrue(runBlocking { provisioner.provisionIfNeeded() })

        assertEquals("registered exactly once", 1, relay.registerCalls.get())
        assertNull("the vault held NO decoy state when register was called", relay.observedAtRegister)
        // Read from the sealed region the sink was handed, not the live state: `mutate` alone only
        // schedules, so a commit that merely mutated would show a complete credential set in RAM
        // and NOTHING here — while a registration had already been spent from a global bucket.
        val decoy = requireNotNull(vault.durableDecoy()) { "a true return means the credentials are on disk" }
        assertEquals("account id committed", relay.issuedAccountId, decoy.accountId)
        assertNotNull("identity keypair committed with it", decoy.identityKeyPair)
        assertEquals("access token committed", "access-1", decoy.accessToken)
        assertEquals("refresh token committed", "refresh-1", decoy.refreshToken)
        assertTrue(decoy.isProvisioned)
        assertNoDanglingReference(vault.runtime)
        assertNoDanglingReferenceOnDisk(vault)
    }

    @Test
    fun `no generation EVER written carries a half credential set`() {
        // The fault injection the old "commits the whole set at once" test lacked. Every mutation
        // is flushed as it happens here, so each intermediate state a multi-step commit passed
        // through would be handed to the sink as its own sealed generation — and a commit that
        // wrote the account id first would produce a generation carrying an id with no identity
        // key. That is the dangling reference: unauthenticatable, undeletable, and the outcome the
        // whole register-before-commit ordering exists to rule out. The live state is never
        // consulted; it looks whole under either implementation.
        val vault = Vault(cooldownMs = 0L, flushContext = kotlinx.coroutines.Dispatchers.Unconfined)
        val relay = FakeRelay()

        assertTrue(runBlocking { provisioner(vault.runtime, relay).provisionIfNeeded() })

        val written = vault.everyDurableDecoy()
        assertTrue("something was actually written (${written.size} generations)", written.isNotEmpty())
        for ((i, decoy) in written.withIndex()) {
            val d = decoy ?: continue
            if (d.accountId != null) {
                assertNotNull("generation $i persisted an account id with NO identity key", d.identityKeyPair)
            }
            if (d.identityKeyPair != null) {
                assertNotNull("generation $i persisted an identity key with NO account id", d.accountId)
            }
        }
        assertTrue(
            "the final generation holds the whole set",
            written.last()?.isProvisioned == true,
        )
    }

    @Test
    fun `a commit that overflows leaves NO half-set on disk`() {
        // The fault injection the old "commits the whole set at once" test lacked. This vault has
        // room for a SMALL section but not for the full credential set: a commit split into two
        // mutates would land the account id durably (it fits) and only then overflow on the
        // identity key, leaving the image the next session opens carrying a dangling reference —
        // exactly the outcome the ordering rule exists to prevent, and invisible to any assertion
        // made against the live state, which holds the retained whole mutation either way.
        val vault = Vault(VaultCapacityFixture(ops).stateWithSlack(200, 400))
        val relay = FakeRelay(tokenPadBytes = REALISTIC_TOKEN_BYTES)

        assertFalse(runBlocking { provisioner(vault.runtime, relay).provisionIfNeeded() })

        assertEquals("the relay account exists (orphaned)", 1, relay.registerCalls.get())
        vault.forceFlush() // anything merely scheduled must be on disk before we judge it
        assertNull("no account id ever reached disk", vault.durableDecoy()?.accountId)
        assertNull("nor an identity key", vault.durableDecoy()?.identityKeyPair)
        assertNoDanglingReferenceOnDisk(vault)
    }

    @Test
    fun `the committed identity key is the one that signed the login challenge`() {
        // Discriminator against a commit that stores SOME keypair: the stored key must be the one
        // the relay actually authenticated, or the account is unusable in every later session.
        val runtime = runtimeOf()
        val relay = FakeRelay()
        assertTrue(runBlocking { provisioner(runtime, relay).provisionIfNeeded() })

        val stored = requireNotNull(runtime.read { it.decoy?.identityKeyPair })
        val challenge = requireNotNull(relay.signedChallenge)
        // XEdDSA signatures are randomized, so re-signing cannot reproduce the bytes. VERIFY
        // instead — which is exactly what the relay does with the identity key it stored.
        assertTrue(
            "the stored key verifies the signature the relay accepted",
            Curve.verifySignature(
                IdentityKeyPair(stored).publicKey.publicKey,
                challenge.toByteArray(Charsets.UTF_8),
                java.util.Base64.getDecoder().decode(requireNotNull(relay.signature)),
            ),
        )
        // Discriminator: a DIFFERENT key must not verify it, or the assertion above would pass for
        // any stored keypair at all.
        assertFalse(
            "an unrelated key does not verify it",
            Curve.verifySignature(
                IdentityKeyPair.generate().publicKey.publicKey,
                challenge.toByteArray(Charsets.UTF_8),
                java.util.Base64.getDecoder().decode(requireNotNull(relay.signature)),
            ),
        )
    }

    @Test
    fun `an already-provisioned vault does no network at all`() {
        val runtime = runtimeOf()
        val relay = FakeRelay()
        assertTrue(runBlocking { provisioner(runtime, relay).provisionIfNeeded() })

        // A later session over the same vault.
        val second = FakeRelay()
        assertTrue(runBlocking { provisioner(runtime, second).provisionIfNeeded() })
        assertEquals("no second registration", 0, second.registerCalls.get())
        assertEquals("no challenge fetched either", 0, second.challengeCalls.get())
    }

    // ── the crash matrix: register-then-commit ────────────────────────────────────

    @Test
    fun `crash BETWEEN register and commit leaves an orphaned relay account, never a reference`() {
        // The named case from the invariant table: the relay accepted the registration and then
        // the session mint died. The account exists on the relay and nothing points at it.
        val runtime = runtimeOf()
        val relay = FakeRelay(failAt = FakeRelay.Stage.SESSION)

        assertFalse(runBlocking { provisioner(runtime, relay).provisionIfNeeded() })

        assertEquals("the relay DID register an account", 1, relay.registerCalls.get())
        assertNotNull("…which is now an orphan", relay.issuedAccountId)
        assertNull("the vault carries no decoy state at all", runtime.read { it.decoy })
        assertNoDanglingReference(runtime)
    }

    @Test
    fun `a failure BEFORE register leaves nothing anywhere`() {
        val runtime = runtimeOf()
        val relay = FakeRelay(failAt = FakeRelay.Stage.CHALLENGE)

        assertFalse(runBlocking { provisioner(runtime, relay).provisionIfNeeded() })

        assertEquals("nothing was registered", 0, relay.registerCalls.get())
        assertNull(runtime.read { it.decoy })
        assertNoDanglingReference(runtime)
    }

    @Test
    fun `a register failure leaves nothing committed`() {
        val runtime = runtimeOf()
        val relay = FakeRelay(failAt = FakeRelay.Stage.REGISTER)

        assertFalse(runBlocking { provisioner(runtime, relay).provisionIfNeeded() })

        assertNull(runtime.read { it.decoy })
        assertNoDanglingReference(runtime)
    }

    @Test
    fun `a commit that cannot be persisted still never splits the credential set`() {
        // A vault already so full that adding the section overflows the fixed region:
        // VaultRuntime RETAINS the mutation in memory, sets capacityExceeded, and rethrows. The
        // credentials are therefore never durable — but they are also never HALF there.
        // Filled to within a few bytes of the region rather than to a guessed size: a fixture that
        // silently left headroom would turn this scenario into the happy path and pass.
        val runtime = runtimeOf(VaultCapacityFixture(ops).stateFilledToCap())
        val relay = FakeRelay()

        assertFalse(
            "a non-durable commit is not a success",
            runBlocking { provisioner(runtime, relay).provisionIfNeeded() },
        )
        assertEquals("the relay account exists (orphaned)", 1, relay.registerCalls.get())
        // Whatever the retained in-memory state says, it is never a half-set.
        assertNoDanglingReference(runtime)
    }

    @Test
    fun `a failed capacity commit does NOT report the vault as provisioned`() {
        // The readiness lie: the retained-but-unscheduled mutation leaves a complete credential
        // pair in the LIVE state, so a readiness check keyed on presence alone answers "ready" for
        // credentials that flushBeforeAck refuses and that lock/process death discards.
        val runtime = runtimeOf(VaultCapacityFixture(ops).stateFilledToCap())
        val relay = FakeRelay()
        val provisioner = provisioner(runtime, relay)

        assertFalse(runBlocking { provisioner.provisionIfNeeded() })

        assertFalse("a non-durable credential set is not provisioned", provisioner.isProvisioned())
        assertFalse(
            "and a second call must not report success either",
            runBlocking { provisioner.provisionIfNeeded() },
        )
        assertEquals("no second registration was spent", 1, relay.registerCalls.get())
    }

    @Test
    fun `a capacity failure backs off DURABLY, so the next session does not register again`() {
        // Without a durable back-off this is one registration per unlock, forever, against a
        // rate-limit bucket that is shared by every client worldwide — systematic and unbounded,
        // not the accepted one-off orphan.
        val vault = Vault(VaultCapacityFixture(ops).stateWithSlack(200, 400))
        val first = FakeRelay(tokenPadBytes = REALISTIC_TOKEN_BYTES)
        assertFalse(runBlocking { provisioner(vault.runtime, first).provisionIfNeeded() })
        assertEquals(1, first.registerCalls.get())

        // The back-off is read from DISK, and the "next session" is built from that image — the
        // only construction in which a scheduled-but-unflushed deferral would show up as absent.
        val persisted = requireNotNull(vault.durableState()) { "a capacity failure must record a back-off" }
        val notBefore = requireNotNull(persisted.decoy?.provisionNotBeforeMs) {
            "the deferral must be on disk, not merely scheduled"
        }
        assertTrue("at least the limiter window", notBefore >= FIXED_NOW + DecoyAccountProvisioner.MIN_BACKOFF_MS)

        val nextSession = FakeRelay()
        val reopened = Vault(persisted)
        assertFalse(
            runBlocking { provisioner(reopened.runtime, nextSession, now = { notBefore - 1 }).provisionIfNeeded() },
        )
        assertEquals("no registration was spent by the next session", 0, nextSession.registerCalls.get())
        assertEquals("not even a challenge was fetched", 0, nextSession.challengeCalls.get())
    }

    @Test
    fun `a capacity failure hands the vault back a flushable state`() {
        // capacityExceeded fail-closes flushBeforeAck for the WHOLE vault, inbound messages
        // included. A cover-traffic write that left it set would convert "no decoys this session"
        // into "this vault can no longer ack a real message".
        val vault = Vault(VaultCapacityFixture(ops).stateWithSlack(200, 400))
        assertFalse(
            runBlocking { provisioner(vault.runtime, FakeRelay(tokenPadBytes = REALISTIC_TOKEN_BYTES)).provisionIfNeeded() },
        )

        assertFalse("the retained mutation was reverted", vault.runtime.capacityExceeded)
        vault.runtime.flushBeforeAck() // would throw if the vault were still over capacity
    }

    @Test
    fun `provisioning never throws, whatever the relay does`() {
        for (thrown in listOf(IOException("offline"), IllegalStateException("weird"), RuntimeException("x"))) {
            val runtime = runtimeOf()
            val relay = FakeRelay(failAt = FakeRelay.Stage.REGISTER, failure = thrown)
            // No try/catch here on purpose: an escape fails the test by propagating.
            assertFalse(runBlocking { provisioner(runtime, relay).provisionIfNeeded() })
            assertNoDanglingReference(runtime)
        }
    }

    // ── registration is a scarce SHARED GLOBAL resource ───────────────────────────

    @Test
    fun `one attempt per session - a failure is not retried inside the session`() {
        val runtime = runtimeOf()
        val relay = FakeRelay(failAt = FakeRelay.Stage.REGISTER)
        val provisioner = provisioner(runtime, relay)

        repeat(5) { assertFalse(runBlocking { provisioner.provisionIfNeeded() }) }

        assertEquals("exactly one registration attempt was spent", 1, relay.registerCalls.get())
    }

    @Test
    fun `a 429 defers provisioning ACROSS sessions, then allows it once the window passes`() {
        // "Across sessions" is a DURABILITY claim, so the next session here is built from the image
        // on disk — not from the same live runtime, which would carry a scheduled-only deferral
        // that a crash inside the coalescing window erases.
        val vault = Vault()
        val limited = FakeRelay(failAt = FakeRelay.Stage.REGISTER, failure = ApiClient.ApiException(429, "rate_limited"))

        assertFalse(runBlocking { provisioner(vault.runtime, limited).provisionIfNeeded() })
        assertEquals(1, limited.registerCalls.get())

        val persisted = requireNotNull(vault.durableState()) {
            "a 429 must PERSIST a deferral, or a crash-and-relaunch hammers a global bucket"
        }
        val notBefore = requireNotNull(persisted.decoy?.provisionNotBeforeMs) {
            "the deferral must be on disk, not merely scheduled"
        }
        assertTrue("deferral is at least the limiter window", notBefore >= FIXED_NOW + DecoyAccountProvisioner.MIN_BACKOFF_MS)
        assertTrue(
            "deferral is bounded",
            notBefore <= FIXED_NOW + DecoyAccountProvisioner.MIN_BACKOFF_MS + DecoyAccountProvisioner.BACKOFF_JITTER_MS,
        )
        assertFalse("a deferral is not a provisioned account", persisted.decoy!!.isProvisioned)

        // A NEW session over what SURVIVED — the shape a crash before the ceiling would leave.
        val crashed = Vault(persisted)
        val nextSession = FakeRelay()
        assertFalse(runBlocking { provisioner(crashed.runtime, nextSession, now = { notBefore - 1 }).provisionIfNeeded() })
        assertEquals("no registration was attempted during the back-off", 0, nextSession.registerCalls.get())
        assertEquals("not even a challenge was fetched", 0, nextSession.challengeCalls.get())

        // Once the window passes, provisioning proceeds and clears the deferral.
        val afterWindow = FakeRelay()
        assertTrue(runBlocking { provisioner(crashed.runtime, afterWindow, now = { notBefore }).provisionIfNeeded() })
        assertEquals(1, afterWindow.registerCalls.get())
        assertNull("a successful provision retires the deferral", crashed.durableDecoy()?.provisionNotBeforeMs)
        assertNoDanglingReference(crashed.runtime)
        assertNoDanglingReferenceOnDisk(crashed)
    }

    @Test
    fun `a back-off window that expires mid-session still gets its one attempt`() {
        // The latch is one ATTEMPT per session, not one CHECK. Burning it on a purely local
        // deferral check means a session that outlives the window makes zero attempts until the
        // next unlock — for a 60–90 minute window and a long-lived session, that is most of the
        // time the user is actually unlocked.
        val vault = Vault()
        val limited = FakeRelay(failAt = FakeRelay.Stage.REGISTER, failure = ApiClient.ApiException(429, "rate_limited"))
        assertFalse(runBlocking { provisioner(vault.runtime, limited).provisionIfNeeded() })
        val notBefore = requireNotNull(vault.durableDecoy()?.provisionNotBeforeMs)

        // ONE provisioner instance — one session — whose clock crosses the window boundary.
        var now = notBefore - 1
        val relay = FakeRelay()
        val sameSession = provisioner(vault.runtime, relay, now = { now })

        assertFalse("inside the window: refused, and no relay contact", runBlocking { sameSession.provisionIfNeeded() })
        assertEquals(0, relay.registerCalls.get())

        now = notBefore
        assertTrue("the window passed, so the attempt is made", runBlocking { sameSession.provisionIfNeeded() })
        assertEquals("exactly one attempt, once it was allowed", 1, relay.registerCalls.get())
    }

    @Test
    fun `the deferral jitters - two rate-limited vaults do not retry in lockstep`() {
        // The bucket is global, so every client is limited at the same instant. A fixed delay
        // would rebuild the same stampede an hour later.
        val deferrals = (0 until 16).map { seed ->
            val runtime = runtimeOf()
            val relay = FakeRelay(failAt = FakeRelay.Stage.REGISTER, failure = ApiClient.ApiException(429, "rate_limited"))
            runBlocking { provisioner(runtime, relay, random = Random(seed.toLong())).provisionIfNeeded() }
            requireNotNull(runtime.read { it.decoy?.provisionNotBeforeMs })
        }
        assertTrue("deferrals are jittered, not identical", deferrals.toSet().size > 1)
    }

    @Test
    fun `a deferral further out than this code can write is treated as a moved clock, not honoured forever`() {
        val runtime = runtimeOf()
        val relay = FakeRelay(failAt = FakeRelay.Stage.REGISTER, failure = ApiClient.ApiException(429, "rate_limited"))
        runBlocking { provisioner(runtime, relay).provisionIfNeeded() }

        // Device clock jumps a decade backwards: the stored deferral is now absurdly far ahead.
        val longAgo = FIXED_NOW - 10L * 365 * 24 * 60 * 60 * 1000
        val recovered = FakeRelay()
        assertTrue(runBlocking { provisioner(runtime, recovered, now = { longAgo }).provisionIfNeeded() })
        assertEquals(1, recovered.registerCalls.get())
    }

    // ── proof-of-work interaction ─────────────────────────────────────────────────

    @Test
    fun `a relay with no proof-of-work endpoint is registered against without a proof`() {
        val runtime = runtimeOf()
        val relay = FakeRelay(challengeToken = null) // the 404 case, mapped to null by the seam
        assertTrue(runBlocking { provisioner(runtime, relay).provisionIfNeeded() })
        assertNull("no proof submitted", relay.submittedProof)
    }

    @Test
    fun `a proof is solved against the SYNTHETIC identity key and submitted`() {
        val runtime = runtimeOf()
        val relay = FakeRelay()
        val solver = FakeSolver()
        val provisioner = DecoyAccountProvisioner(
            runtime = runtime,
            relay = relay,
            powSolver = solver,
            clock = { FIXED_NOW },
            random = Random(7L),
        )
        assertTrue(runBlocking { provisioner.provisionIfNeeded() })

        assertEquals("the fetched challenge was solved", "challenge-token", solver.solvedChallenge)
        val stored = requireNotNull(runtime.read { it.decoy?.identityKeyPair })
        assertTrue(
            "the proof bound the synthetic account's own identity key, not the vault's",
            DecoyIdentity.publicKeyBytes(stored).contentEquals(solver.boundIdentityKey),
        )
        assertNotNull("the proof reached the register call", relay.submittedProof)
    }

    // ── token refresh (W2) ────────────────────────────────────────────────────────

    @Test
    fun `refreshTokens rotates through the refresh endpoint and stores only token fields`() {
        val runtime = runtimeOf()
        val relay = FakeRelay()
        val provisioner = provisioner(runtime, relay)
        runBlocking { provisioner.provisionIfNeeded() }
        val accountId = runtime.read { it.decoy?.accountId }
        val identity = runtime.read { it.decoy?.identityKeyPair }?.copyOf()

        assertTrue(runBlocking { provisioner.refreshTokens() })

        runtime.read { state ->
            val decoy = requireNotNull(state.decoy)
            assertEquals("refreshed access token stored", "access-2", decoy.accessToken)
            assertEquals("refreshed refresh token stored", "refresh-2", decoy.refreshToken)
            assertEquals("account id untouched", accountId, decoy.accountId)
            assertTrue("identity key untouched", identity.contentEquals(decoy.identityKeyPair))
        }
    }

    @Test
    fun `refreshTokens falls back to a full login when the refresh token is dead`() {
        // The 7-day refresh TTL means a vault left locked for longer ALWAYS lands here; possession
        // of the identity key is what makes the fallback always available.
        val runtime = runtimeOf()
        val relay = FakeRelay()
        val provisioner = provisioner(runtime, relay)
        runBlocking { provisioner.provisionIfNeeded() }

        relay.refreshFails = true
        assertTrue(runBlocking { provisioner.refreshTokens() })
        assertEquals("a fresh session was minted instead", 2, relay.sessionCalls.get())
        assertEquals("the freshly minted token was stored", "access-2", runtime.read { it.decoy?.accessToken })
    }

    @Test
    fun `refreshTokens on an unprovisioned vault is a silent no-op`() {
        val runtime = runtimeOf()
        val relay = FakeRelay()
        assertFalse(runBlocking { provisioner(runtime, relay).refreshTokens() })
        assertEquals("no network at all", 0, relay.sessionCalls.get())
        assertNull(runtime.read { it.decoy })
    }

    @Test
    fun `nothing decoy-related touches the vault's ordinary account section`() {
        val runtime = runtimeOf(
            VaultState.empty().also { it.auth = com.zitrone.app.data.AuthState("real-acct", "real-access", "real-refresh") },
        )
        val provisioner = provisioner(runtime, FakeRelay())
        runBlocking { provisioner.provisionIfNeeded() }
        runBlocking { provisioner.refreshTokens() }

        runtime.read { state ->
            assertEquals("real account id untouched", "real-acct", state.auth.accountId)
            assertEquals("real access token untouched", "real-access", state.auth.accessToken)
            assertEquals("real refresh token untouched", "real-refresh", state.auth.refreshToken)
        }
    }

    // ── fakes ─────────────────────────────────────────────────────────────────────

    /**
     * A relay that can fail at any stage of the boot-shaped sequence and records what it saw.
     * [observeAtRegister] runs at the instant the registration lands, which is how the tests
     * observe the register-before-commit ordering rather than assuming it.
     */
    private class FakeRelay(
        private val challengeToken: String? = "challenge-token",
        private val failAt: Stage? = null,
        private val failure: Throwable = IOException("boom"),
        private val observeAtRegister: (() -> Any?)? = null,
        /**
         * Extra random bytes of token, base64'd — the capacity scenarios need a credential set of
         * REALISTIC size (an RS256 access JWT is ~530 chars), because the whole point there is that
         * the whole set does not fit where a lone account id would. Random rather than repeated, so
         * DEFLATE cannot squash it back down to nothing. Zero keeps the short, readable defaults
         * every other test asserts on.
         */
        private val tokenPadBytes: Int = 0,
    ) : DecoyRelayApi {

        private val tokenPadding = Random(11L)

        private fun token(kind: String, n: Int): String =
            if (tokenPadBytes == 0) {
                "$kind-$n"
            } else {
                val raw = ByteArray(tokenPadBytes).also(tokenPadding::nextBytes)
                "$kind-$n." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
            }

        enum class Stage { CHALLENGE, REGISTER, SESSION }

        val challengeCalls = AtomicInteger(0)
        val registerCalls = AtomicInteger(0)
        val sessionCalls = AtomicInteger(0)
        var issuedAccountId: String? = null
        var submittedProof: Map<String, String>? = null
        var observedAtRegister: Any? = null
        var signedChallenge: String? = null
        var signature: String? = null
        var refreshFails = false

        override suspend fun registrationChallenge(): String? {
            challengeCalls.incrementAndGet()
            if (failAt == Stage.CHALLENGE) throw failure
            return challengeToken
        }

        override suspend fun register(material: DecoyIdentity.Material, powProof: Map<String, String>?): String {
            observedAtRegister = observeAtRegister?.invoke()
            registerCalls.incrementAndGet()
            if (failAt == Stage.REGISTER) throw failure
            submittedProof = powProof
            val id = "22222222-3333-4444-5555-666666666666"
            issuedAccountId = id
            return id
        }

        override suspend fun createSession(
            accountId: String,
            signChallenge: (String) -> String,
        ): ApiClient.SessionTokens {
            val n = sessionCalls.incrementAndGet()
            if (failAt == Stage.SESSION) throw failure
            // Exercise the signing callback for real: the challenge shape mirrors the server's.
            val challenge = "sublemonable-login:$accountId:1795000000"
            signedChallenge = challenge
            signature = signChallenge(challenge)
            return ApiClient.SessionTokens(token("access", n), token("refresh", n))
        }

        override suspend fun refreshSession(refreshToken: String): ApiClient.SessionTokens {
            if (refreshFails) throw ApiClient.ApiException(401, "unauthorized")
            return ApiClient.SessionTokens("access-2", "refresh-2")
        }
    }

    /** Records what it was asked to solve; returns a fixed wire-shaped proof. */
    private class FakeSolver : DecoyPowSolver {
        var solvedChallenge: String? = null
        var boundIdentityKey: ByteArray? = null

        override suspend fun solve(challengeToken: String, identityKeyBytes: ByteArray): Map<String, String> {
            solvedChallenge = challengeToken
            boundIdentityKey = identityKeyBytes.copyOf()
            return mapOf(
                "challenge_token" to challengeToken,
                "hashcash_nonce" to "AAAAAAAAAAA=",
                "argon_nonce" to "BBBBBBBBBBBBBBBBBBBBBB==",
            )
        }
    }

    private companion object {
        /** A fixed "now" so deferral arithmetic is exact rather than wall-clock dependent. */
        const val FIXED_NOW = 1_795_000_000_000L

        /**
         * Token padding that makes the fake's credential set as big as a real one's — the relay
         * issues an RS256 access JWT of ~530 chars. The capacity scenarios depend on the WHOLE set
         * not fitting where a lone account id would, so a fake with 8-char tokens would quietly
         * turn them into the happy path.
         */
        const val REALISTIC_TOKEN_BYTES = 300
    }
}
