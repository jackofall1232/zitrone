// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.DecoyState
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
import com.zitrone.app.crypto.vault.VaultCapacityException
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import com.zitrone.app.crypto.vault.openPayload
import com.zitrone.app.decoy.DecoyAccountProvisioner
import com.zitrone.app.decoy.DecoyCounterReservation
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        /**
         * Runs at the START of every persist, before the generation is recorded, so a test can make
         * a chosen durable write fail. Throwing here is what a real `flushBeforeAck` failure looks
         * like from the runtime's side.
         */
        private val onPersist: () -> Unit = {},
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
            persist = { _, sealed ->
                onPersist()
                generations += sealed.copyOf()
            },
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
        val relay = FakeRelay(observeAtRegister = { vault.runtime.read { it.decoy?.isProvisioned == true } })
        val provisioner = provisioner(vault.runtime, relay)

        assertTrue(runBlocking { provisioner.provisionIfNeeded() })

        assertEquals("registered exactly once", 1, relay.registerCalls.get())
        // The section DOES exist by then — it carries the write-ahead back-off — but it references
        // no account, which is the ordering property this asserts.
        assertEquals("the vault referenced NO account when register was called", false, relay.observedAtRegister)
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
        assertNull("the vault references no account", runtime.read { it.decoy?.accountId })
        assertNull("and holds no identity key", runtime.read { it.decoy?.identityKeyPair })
        assertNotNull(
            "the write-ahead back-off stands, so the orphan is not repeated next unlock",
            runtime.read { it.decoy?.provisionNotBeforeMs },
        )
        assertNoDanglingReference(runtime)
    }

    @Test
    fun `a failure BEFORE register leaves no credentials — only the write-ahead back-off`() {
        val runtime = runtimeOf()
        val relay = FakeRelay(failAt = FakeRelay.Stage.CHALLENGE)

        assertFalse(runBlocking { provisioner(runtime, relay).provisionIfNeeded() })

        assertEquals("nothing was registered", 0, relay.registerCalls.get())
        val decoy = requireNotNull(runtime.read { it.decoy }) {
            "the attempt was recorded BEFORE the relay was contacted"
        }
        assertNull("no account id", decoy.accountId)
        assertNull("no identity key", decoy.identityKeyPair)
        assertNotNull("and the back-off stands, because only a success retires it", decoy.provisionNotBeforeMs)
        assertNoDanglingReference(runtime)
    }

    @Test
    fun `a register failure leaves no credentials committed`() {
        val runtime = runtimeOf()
        val relay = FakeRelay(failAt = FakeRelay.Stage.REGISTER)

        assertFalse(runBlocking { provisioner(runtime, relay).provisionIfNeeded() })

        assertNull("no account id", runtime.read { it.decoy?.accountId })
        assertNull("no identity key", runtime.read { it.decoy?.identityKeyPair })
        assertNoDanglingReference(runtime)
    }

    @Test
    fun `a vault too full to record a back-off never spends a registration at all`() {
        // The absolute-capacity edge, and the reason the back-off is written FIRST. Round 1 wrote
        // it in the capacity handler, so a vault with no room for even a deferral bare-reverted and
        // left NOTHING on disk saying it had tried — one fresh registration against the shared
        // worldwide bucket on every single unlock, forever. Writing the back-off before any relay
        // contact makes "cannot record that I tried" mean "do not try": there is no edge left where
        // nothing can be encoded, because nothing has been spent by then.
        // Filled to within a few bytes of the region rather than to a guessed size: a fixture that
        // silently left headroom would turn this scenario into the happy path and pass.
        val vault = Vault(VaultCapacityFixture(ops).stateFilledToCap())
        val relay = FakeRelay()

        assertFalse(runBlocking { provisioner(vault.runtime, relay).provisionIfNeeded() })

        assertEquals("no registration was spent", 0, relay.registerCalls.get())
        assertEquals("not even a challenge was fetched", 0, relay.challengeCalls.get())
        // …and the vault is handed back usable: an unscheduled over-capacity mutation would
        // fail-close flushBeforeAck for the WHOLE vault, real inbound messages included.
        assertFalse("the failed back-off write was reverted", vault.runtime.capacityExceeded)
        vault.runtime.flushBeforeAck()
        assertNoDanglingReference(vault.runtime)

        // The next unlock over the SAME image spends nothing either — the property round-1 F4 was
        // meant to give and did not, on this edge.
        val next = FakeRelay()
        val reopened = Vault(VaultCapacityFixture(ops).stateFilledToCap())
        assertFalse(runBlocking { provisioner(reopened.runtime, next).provisionIfNeeded() })
        assertEquals("nor does the next session", 0, next.registerCalls.get())
    }

    @Test
    fun `a commit that cannot be persisted still never splits the credential set`() {
        // A vault with room for the back-off but not for the credential set: VaultRuntime RETAINS
        // the mutation in memory, sets capacityExceeded, and rethrows. The credentials are
        // therefore never durable — but they are also never HALF there.
        val vault = Vault(VaultCapacityFixture(ops).stateWithSlack(200, 400))
        val relay = FakeRelay(tokenPadBytes = REALISTIC_TOKEN_BYTES)

        assertFalse(
            "a non-durable commit is not a success",
            runBlocking { provisioner(vault.runtime, relay).provisionIfNeeded() },
        )
        assertEquals("the relay account exists (orphaned)", 1, relay.registerCalls.get())
        // Whatever the retained in-memory state says, it is never a half-set.
        assertNoDanglingReference(vault.runtime)
        assertNoDanglingReferenceOnDisk(vault)
    }

    @Test
    fun `an unrelated capacity overflow stops SENDING without re-entering registration`() {
        // The predicate split, and the defect that forced it. Round 1 folded capacityExceeded into
        // one isProvisioned() and gated BOTH questions on it, so an overflow caused by a completely
        // unrelated write made a vault that already held durable synthetic credentials answer "not
        // provisioned" — take the latch, and register a SECOND account against a rate-limit bucket
        // shared by every client worldwide. Refusing to send is right; refusing to acknowledge an
        // account that already exists is not.
        val vault = Vault()
        assertTrue(runBlocking { provisioner(vault.runtime, FakeRelay()).provisionIfNeeded() })
        val accountId = requireNotNull(vault.runtime.read { it.decoy?.accountId })

        // A LATER session over the same vault — a fresh instance, so its one-attempt latch is
        // unburned and nothing but the predicate stands between it and the relay.
        val relay = FakeRelay()
        val provisioner = provisioner(vault.runtime, relay)
        assertTrue("the account is durable and sendable", provisioner.canSend())

        // An UNRELATED write overflows the region: nothing to do with cover traffic.
        // Random, not patterned: a repeating byte sequence DEFLATEs to nothing and would quietly
        // turn this scenario into the happy path.
        val bulk = ByteArray(VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES).also { Random(3L).nextBytes(it) }
        assertThrows(VaultCapacityException::class.java) {
            vault.runtime.mutate { it.signalRecords["bulk"] = bulk }
        }
        assertTrue("the fixture really did overflow", vault.runtime.capacityExceeded)

        assertTrue("the account did not stop existing", provisioner.hasAccount())
        assertFalse("but nothing decoy-related can be made durable, so do not send", provisioner.canSend())
        assertFalse("and provisionIfNeeded reports the send predicate", runBlocking { provisioner.provisionIfNeeded() })

        // …and now the case that makes this a REGISTRATION defect rather than only a send one:
        // the flag is runtime-wide and clears on the next successful mutate, so "the flag is set
        // AND the state would now encode" is a real, reachable window — it is exactly the instant
        // before whichever write brings the state back under the cap lands. (Reached here by taking
        // the offending record back out of the live state without a mutate, which is the only way
        // to hold that window still.) A register predicate keyed on the flag walks straight into it.
        val stillSet = vault.runtime.read { it.signalRecords.remove("bulk"); it }
        assertTrue("the live state fits again", VaultStateCodec.encode(stillSet).isNotEmpty())
        assertTrue("but no mutate has cleared the flag yet", vault.runtime.capacityExceeded)

        // Yet another session, so the one-attempt latch is not what is doing the work here.
        val later = FakeRelay()
        assertFalse(runBlocking { provisioner(vault.runtime, later).provisionIfNeeded() })
        assertEquals(
            "NOT ONE registration was spent from the shared global bucket",
            0,
            later.registerCalls.get(),
        )
        assertEquals("nor even a challenge", 0, later.challengeCalls.get())
        assertEquals(
            "and the durable account was not replaced by a second one",
            accountId,
            vault.runtime.read { it.decoy?.accountId },
        )
        assertEquals("no registration in the earlier session either", 0, relay.registerCalls.get())
    }

    @Test
    fun `a credential commit whose flush THROWS is never reported as ready`() {
        // "A flush throw means it never happened" held for the call that saw it and not for the
        // next one: the credentials sat live with capacityExceeded clear, so a second readiness
        // check answered "ready" on bytes no reader will ever find on disk.
        // Persist #1 is the write-ahead back-off, which must succeed or nothing is registered at
        // all; #2 is the credential commit's flush, and that is the one made to throw.
        var persists = 0
        val vault = Vault(onPersist = { if (++persists >= 2) throw IOException("disk full") })
        val relay = FakeRelay()
        val provisioner = provisioner(vault.runtime, relay)

        assertFalse("the call that saw the throw reports failure", runBlocking { provisioner.provisionIfNeeded() })
        assertTrue("the account exists — a second registration must NOT be spent", provisioner.hasAccount())
        assertFalse("but it was never confirmed durable, so it may not be sent on", provisioner.canSend())
        assertFalse("and the next call must not flip to ready", runBlocking { provisioner.provisionIfNeeded() })
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
    fun `a capacity revert restores what the section held AT COMMIT TIME, not a pre-network snapshot`() {
        // Round 1 snapshotted the section before the relay sequence and restored that snapshot on a
        // capacity failure — seconds of proof-of-work and HTTP later. Anything the section gained in
        // that window was clobbered wholesale, and the worst case is a counter reservation: the mark
        // goes BACKWARDS, and the allocator hands out values that were already spent. A cleartext
        // message_number regression is the exact tell the whole counter discipline exists to deny a
        // relay operator.
        //
        // The concurrent write is driven from inside the relay call, which is precisely the window:
        // the provisioner holds no lock there, by design, because the alternative is stalling the
        // send path behind a multi-second registration.
        val vault = Vault(VaultCapacityFixture(ops).stateWithSlack(200, 400))
        val issued = mutableListOf<Long>()
        val relay = FakeRelay(
            tokenPadBytes = REALISTIC_TOKEN_BYTES,
            duringRegister = {
                issued += DecoyCounterReservation.forRuntime(vault.runtime, blockSize = 4).next()
            },
        )

        assertFalse(runBlocking { provisioner(vault.runtime, relay).provisionIfNeeded() })

        assertEquals("a counter really was issued during the round-trip", listOf(0L), issued)
        assertEquals(
            "the reservation the revert had to preserve is still the live mark",
            4L,
            vault.runtime.read { it.decoy?.counterHighWater ?: 0L },
        )
        // And the wire property itself: the next value must never be one already handed out.
        val next = DecoyCounterReservation.forRuntime(vault.runtime, blockSize = 4).next()
        assertTrue("counter $next was already issued — a REGRESSION", next !in issued)
        assertTrue("and it does not go backwards", next > issued.max())
    }

    @Test
    fun `the loser of the one-attempt latch reports the truth, not a flat false`() {
        // Two callers on one instance: the loser used to return false even after the winner had
        // provisioned successfully — a silent decoys-off for the rest of that call chain.
        // The interleaving is made exact through the injected clock: an EXPIRED deferral is the one
        // state in which isDeferred() consults it, which gives a suspension point between the
        // loser's deferral check and its compare-and-set.
        val runtime = runtimeOf(
            VaultState.empty().also { it.decoy = DecoyState(provisionNotBeforeMs = FIXED_NOW - 1) },
        )
        val relay = FakeRelay()
        val loserThread = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
        val armed = java.util.concurrent.atomic.AtomicBoolean(true)
        val loserReachedTheCheck = CountDownLatch(1)
        val winnerDone = CountDownLatch(1)

        val provisioner = provisioner(runtime, relay, now = {
            if (Thread.currentThread() === loserThread.get() && armed.compareAndSet(true, false)) {
                loserReachedTheCheck.countDown()
                check(winnerDone.await(30, TimeUnit.SECONDS)) { "the winner never finished" }
            }
            FIXED_NOW
        })

        var loserResult: Boolean? = null
        val loser = Thread { loserResult = runBlocking { provisioner.provisionIfNeeded() } }
        loserThread.set(loser)
        loser.start()
        assertTrue("the loser reached its deferral check", loserReachedTheCheck.await(30, TimeUnit.SECONDS))

        assertTrue("the winner provisions", runBlocking { provisioner.provisionIfNeeded() })
        winnerDone.countDown()
        loser.join(30_000)

        assertEquals("exactly one registration between them", 1, relay.registerCalls.get())
        assertEquals("the loser reports the vault as sendable, because it IS", true, loserResult)
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
         * Runs INSIDE the register call — i.e. inside the window where the provisioner deliberately
         * holds no lock, so a test can drive a genuinely concurrent decoy write into it.
         */
        private val duringRegister: (() -> Unit)? = null,
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
            duringRegister?.invoke()
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
