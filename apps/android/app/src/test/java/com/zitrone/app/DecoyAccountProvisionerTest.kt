// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
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

    private fun runtimeOf(state: VaultState = VaultState.empty()): VaultRuntime {
        val session = VaultSession(
            scope = scope,
            ops = ops,
            initialPayload = VaultStateCodec.encode(state),
            initialVaultKey = ByteArray(VAULT_KEY_BYTES) { 0x11 },
            slotIndex = 0,
            persist = { _, _ -> },
            cooldownMs = 60_000L,
            flushContext = Dispatchers.IO,
        )
        return VaultRuntime(session, state)
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
    fun `provisioning registers on the relay BEFORE it commits, and commits the whole set at once`() {
        val runtime = runtimeOf()
        // The fake reads the vault at the moment the relay call lands, so "register precedes
        // commit" is observed rather than inferred from the code's shape.
        val relay = FakeRelay(observeAtRegister = { runtime.read { it.decoy } })
        val provisioner = provisioner(runtime, relay)

        assertTrue(runBlocking { provisioner.provisionIfNeeded() })

        assertEquals("registered exactly once", 1, relay.registerCalls.get())
        assertNull("the vault held NO decoy state when register was called", relay.observedAtRegister)
        runtime.read { state ->
            val decoy = requireNotNull(state.decoy)
            assertEquals("account id committed", relay.issuedAccountId, decoy.accountId)
            assertNotNull("identity keypair committed with it", decoy.identityKeyPair)
            assertEquals("access token committed", "access-1", decoy.accessToken)
            assertEquals("refresh token committed", "refresh-1", decoy.refreshToken)
            assertTrue(decoy.isProvisioned)
        }
        assertNoDanglingReference(runtime)
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
        assertTrue("the runtime knows the state is unscheduled", runtime.capacityExceeded)
        assertEquals("the relay account exists (orphaned)", 1, relay.registerCalls.get())
        // Whatever the retained in-memory state says, it is never a half-set.
        assertNoDanglingReference(runtime)
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
        val runtime = runtimeOf()
        val limited = FakeRelay(failAt = FakeRelay.Stage.REGISTER, failure = ApiClient.ApiException(429, "rate_limited"))

        assertFalse(runBlocking { provisioner(runtime, limited).provisionIfNeeded() })
        assertEquals(1, limited.registerCalls.get())

        val notBefore = requireNotNull(runtime.read { it.decoy?.provisionNotBeforeMs }) {
            "a 429 must persist a deferral, or the next session hammers a global bucket"
        }
        assertTrue("deferral is at least the limiter window", notBefore >= FIXED_NOW + DecoyAccountProvisioner.MIN_BACKOFF_MS)
        assertTrue(
            "deferral is bounded",
            notBefore <= FIXED_NOW + DecoyAccountProvisioner.MIN_BACKOFF_MS + DecoyAccountProvisioner.BACKOFF_JITTER_MS,
        )
        assertFalse("a deferral is not a provisioned account", runtime.read { it.decoy!!.isProvisioned })

        // A NEW session (new provisioner instance, same vault) inside the window must not register.
        val nextSession = FakeRelay()
        assertFalse(runBlocking { provisioner(runtime, nextSession, now = { notBefore - 1 }).provisionIfNeeded() })
        assertEquals("no registration was attempted during the back-off", 0, nextSession.registerCalls.get())
        assertEquals("not even a challenge was fetched", 0, nextSession.challengeCalls.get())

        // Once the window passes, provisioning proceeds and clears the deferral.
        val afterWindow = FakeRelay()
        assertTrue(runBlocking { provisioner(runtime, afterWindow, now = { notBefore }).provisionIfNeeded() })
        assertEquals(1, afterWindow.registerCalls.get())
        assertNull("a successful provision retires the deferral", runtime.read { it.decoy?.provisionNotBeforeMs })
        assertNoDanglingReference(runtime)
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
    ) : DecoyRelayApi {

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
            return ApiClient.SessionTokens("access-$n", "refresh-$n")
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
    }
}
