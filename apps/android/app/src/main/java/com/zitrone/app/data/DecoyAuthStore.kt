// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.data

import com.zitrone.app.crypto.vault.DecoyState
import com.zitrone.app.crypto.vault.VaultRuntime

/**
 * [AuthStore] over the vault's cover-traffic section (`TAG_DECOY`) rather than its ordinary
 * account section — the behavioural twin of [VaultAuthStore], one section over.
 *
 * Every read/write goes through [VaultRuntime]'s single lock, so it is safe from any thread and
 * a reader never sees a torn account/token pair. Writes are COALESCED (non-forced), matching
 * [VaultAuthStore]: these tokens are recoverable by re-minting a session from the stored
 * identity key, so they never need flush-before-ack.
 *
 * ⚠️ THE [accountId] SETTER IS FAIL-CLOSED, AND THAT IS THE POINT. `ApiClient.register()` writes
 * the new account id into its store the instant the 201 lands, BEFORE anything else about the
 * account is persisted. Registering through this store would therefore commit an account id with
 * NO identity keypair — an account this client can never authenticate to and never delete, which
 * breaks every subsequent decoy send. The ordering rule is: **register on the relay first, then
 * commit the whole credential set in ONE mutate**, so a failure leaves an orphaned relay account
 * (harmless) rather than a dangling reference. Provisioning therefore runs its client against a
 * RAM-only [StagingAuthStore]; this store is for an ALREADY-provisioned account. A setter call
 * that would change the id is refused, which converts the dangerous wiring into the accepted
 * orphan outcome instead of letting it persist silently.
 */
class DecoyAuthStore(
    private val runtime: VaultRuntime,
) : AuthStore {

    override var accountId: String?
        get() = runtime.read { it.decoy?.accountId }
        set(value) {
            // Idempotent re-assert of the SAME id is allowed and is a genuine no-op — hence a
            // `read`, not a `mutate`: re-encoding and rescheduling the whole state to write a value
            // that is already there would be pure churn. Anything else is the dangling-reference
            // path described in the class kdoc, and is refused.
            runtime.read {
                val current = it.decoy?.accountId
                check(value == current) {
                    "cover-traffic account id is committed with its identity key, never separately"
                }
            }
        }

    override val accessToken: String?
        get() = runtime.read { it.decoy?.accessToken }

    override val refreshToken: String?
        get() = runtime.read { it.decoy?.refreshToken }

    override fun storeTokens(access: String, refresh: String) {
        runtime.mutate {
            it.decoy = (it.decoy ?: DecoyState()).copy(accessToken = access, refreshToken = refresh)
        }
    }

    override fun clearTokens() {
        runtime.mutate {
            // Only rewrite when a holder already exists: clearing tokens on a vault that has no
            // cover-traffic state must not CREATE the section. An empty section is omitted by the
            // codec anyway, but not materialising it keeps the intent explicit.
            it.decoy?.let { current -> it.decoy = current.copy(accessToken = null, refreshToken = null) }
        }
    }

    override fun clearAccount() {
        runtime.mutate {
            // Drop the whole credential set together, mirroring how it was committed: an account
            // id and its identity key are never separated in either direction.
            it.decoy?.let { current ->
                current.wipe()
                it.decoy = current.copy(accountId = null, identityKeyPair = null)
            }
        }
    }
}

/**
 * A RAM-only [AuthStore] — the staging area a registration runs against so nothing durable is
 * written until the whole credential set can be committed at once (see [DecoyAuthStore]'s kdoc
 * for why that ordering is load-bearing).
 *
 * Not thread-safe by contract in the sense that matters here: one provisioning attempt owns one
 * instance and drives it from a single coroutine. The fields are `@Volatile` anyway so a value
 * written on one dispatcher thread is visible to the next.
 */
class StagingAuthStore : AuthStore {

    @Volatile
    override var accountId: String? = null

    @Volatile
    private var access: String? = null

    @Volatile
    private var refresh: String? = null

    override val accessToken: String? get() = access

    override val refreshToken: String? get() = refresh

    override fun storeTokens(access: String, refresh: String) {
        this.access = access
        this.refresh = refresh
    }

    override fun clearTokens() {
        access = null
        refresh = null
    }

    override fun clearAccount() {
        accountId = null
    }
}
