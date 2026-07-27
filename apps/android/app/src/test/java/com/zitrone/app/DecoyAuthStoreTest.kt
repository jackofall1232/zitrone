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
