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
import com.zitrone.app.data.AuthState
import com.zitrone.app.data.DecoyAuthStore
import com.zitrone.app.data.StagingAuthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [DecoyAuthStore] — the cover-traffic account's token surface, and the fail-closed setter that
 * keeps the ordering rule enforceable.
 *
 * `ApiClient.register()` writes an account id into its store the instant the 201 lands. Wiring a
 * registration to the vault-backed store would therefore persist an account id with no identity
 * key — a reference this client could never authenticate to, and the one outcome the provisioning
 * order exists to prevent. The setter refuses, which converts that wiring mistake into the
 * accepted orphan outcome instead of a silent, permanent one.
 */
class DecoyAuthStoreTest {

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

    private fun provisioned(): VaultState = VaultState.empty().also {
        it.auth = AuthState("real-acct", "real-access", "real-refresh")
        it.decoy = DecoyState(
            accountId = "synthetic-acct",
            identityKeyPair = ByteArray(68) { b -> (b + 1).toByte() },
            accessToken = "a0",
            refreshToken = "r0",
        )
    }

    @Test
    fun `reads and token writes address the decoy section, never the ordinary account`() {
        val state = provisioned()
        val runtime = runtimeOf(state)
        val store = DecoyAuthStore(runtime)

        assertEquals("synthetic-acct", store.accountId)
        assertEquals("a0", store.accessToken)
        assertEquals("r0", store.refreshToken)

        store.storeTokens("a1", "r1")

        runtime.read {
            assertEquals("a1", it.decoy?.accessToken)
            assertEquals("r1", it.decoy?.refreshToken)
            assertEquals("the ordinary account's tokens are untouched", "real-access", it.auth.accessToken)
            assertEquals("real-refresh", it.auth.refreshToken)
            assertEquals("and its id", "real-acct", it.auth.accountId)
        }
    }

    @Test
    fun `setting a DIFFERENT account id is refused - a credential set is never split`() {
        val runtime = runtimeOf(provisioned())
        val store = DecoyAuthStore(runtime)

        assertThrows(IllegalStateException::class.java) { store.accountId = "some-other-account" }
        assertEquals("the stored id is unchanged", "synthetic-acct", runtime.read { it.decoy?.accountId })
    }

    @Test
    fun `setting the id on an unprovisioned vault is refused, and creates nothing`() {
        // This is the exact shape a registration wired to this store would take.
        val runtime = runtimeOf()
        val store = DecoyAuthStore(runtime)

        assertThrows(IllegalStateException::class.java) { store.accountId = "freshly-registered" }
        assertNull("no section was materialised", runtime.read { it.decoy })
    }

    @Test
    fun `re-asserting the SAME id is a no-op, not a refusal`() {
        val runtime = runtimeOf(provisioned())
        val store = DecoyAuthStore(runtime)
        store.accountId = "synthetic-acct"
        assertEquals("synthetic-acct", runtime.read { it.decoy?.accountId })
    }

    @Test
    fun `tokens are never written for an account this vault does not hold`() {
        // [R3] Tokens belong TO an account. A token write that materialises its own section hands
        // the vault live bearer credentials for something it does not claim — and, on a vault that
        // never provisioned, a TAG_DECOY section that costs it its 0.9.x readability for nothing.
        val empty = runtimeOf()
        DecoyAuthStore(empty).storeTokens("a1", "r1")
        assertNull("no section was materialised", empty.read { it.decoy })

        // And the conditional form, which is what a token refresh racing clearAccount runs into:
        // the account it minted those tokens for is no longer the account this vault holds.
        val runtime = runtimeOf(provisioned())
        val store = DecoyAuthStore(runtime)
        assertEquals(
            "a refresh for the CURRENT account still lands",
            true,
            store.storeTokensForAccount("synthetic-acct", "a1", "r1"),
        )
        assertEquals("a1", runtime.read { it.decoy?.accessToken })

        assertEquals(
            "…but one for a different account is refused",
            false,
            store.storeTokensForAccount("some-other-account", "a2", "r2"),
        )
        runtime.read {
            assertEquals("the live tokens were not replaced", "a1", it.decoy?.accessToken)
            assertEquals("r1", it.decoy?.refreshToken)
        }
    }

    @Test
    fun `clearTokens drops only the tokens, and never creates a section`() {
        val runtime = runtimeOf(provisioned())
        DecoyAuthStore(runtime).clearTokens()
        runtime.read {
            assertNull(it.decoy?.accessToken)
            assertNull(it.decoy?.refreshToken)
            assertEquals("credentials survive a token clear", "synthetic-acct", it.decoy?.accountId)
        }

        val empty = runtimeOf()
        DecoyAuthStore(empty).clearTokens()
        assertNull("clearing tokens on a vault with no section creates none", empty.read { it.decoy })
    }

    @Test
    fun `clearAccount drops the id and ZEROES the identity key together`() {
        val state = provisioned()
        val identity = requireNotNull(state.decoy?.identityKeyPair)
        val runtime = runtimeOf(state)

        DecoyAuthStore(runtime).clearAccount()

        runtime.read {
            assertNull("account id gone", it.decoy?.accountId)
            assertNull("identity key gone with it", it.decoy?.identityKeyPair)
        }
        assertArrayEquals("the private key bytes were zeroed, not merely dropped", ByteArray(identity.size), identity)
    }

    @Test
    fun `clearAccount drops the SESSION TOKENS too, or the account is not cleared at all`() {
        // Round 1 retained them. A retired account whose live bearer credentials survive is not
        // retired: the access JWT keeps authenticating it until it expires, and the refresh token
        // mints a whole new session from it — for an account the caller has just said it wants gone.
        val runtime = runtimeOf(provisioned())
        val store = DecoyAuthStore(runtime)
        assertEquals("the fixture really holds live tokens", "a0", store.accessToken)

        store.clearAccount()

        runtime.read {
            assertNull("the access token went with the account", it.decoy?.accessToken)
            assertNull("and so did the refresh token", it.decoy?.refreshToken)
        }
    }

    @Test
    fun `clearAccount resets the counter mark so a replacement account starts at zero`() {
        // The mark describes ONE synthetic peer. Carried across a re-provision, the replacement
        // account's first envelope would arrive carrying message_number = 128 in the clear on a
        // brand-new session — something no real Double Ratchet with a new recipient produces, and
        // therefore a free classifier for the relay operator.
        val state = provisioned().also { it.decoy = it.decoy!!.copy(counterHighWater = 128L) }
        val runtime = runtimeOf(state)

        DecoyAuthStore(runtime).clearAccount()

        assertEquals("the counter mark went with the account", 0L, runtime.read { it.decoy?.counterHighWater ?: 0L })
    }

    @Test
    fun `the staging store holds everything in RAM and writes nothing durable`() {
        val runtime = runtimeOf()
        val staging = StagingAuthStore()

        staging.accountId = "freshly-registered"
        staging.storeTokens("a", "r")

        assertEquals("freshly-registered", staging.accountId)
        assertEquals("a", staging.accessToken)
        assertEquals("r", staging.refreshToken)
        assertNull("the vault saw none of it", runtime.read { it.decoy })

        staging.clearTokens()
        assertNull(staging.accessToken)
        assertNull(staging.refreshToken)
        staging.clearAccount()
        assertNull(staging.accountId)
    }
}
