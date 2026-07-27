// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.data

import com.zitrone.app.crypto.vault.DecoySectionLock
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
 * ⚠️ EVERY WRITE HERE TAKES [DecoySectionLock] **[R2]**. `stateLock` alone makes each `mutate`
 * atomic, which is the wrong granularity: [clearAccount] resets `counterHighWater`, and
 * `DecoyCounterReservation` checks that mark in one call and spends against it in the next. With
 * only the runtime lock, a clear landing between the check and the spend lets the allocator issue
 * from a block the mark no longer covers — `1, 0` on the wire, a cleartext counter regression.
 * Taking the section monitor makes this write exclusive against the allocator's whole sequence and
 * against the provisioner's read-commit-revert. Reads do NOT take it: `runtime.read` is already
 * atomic, and a caller acting on a stale single value is the caller's own race.
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
 *
 * ⚠️ **TOKEN WRITES ARE FAIL-CLOSED THE SAME WAY [R3].** Tokens belong to an account: [storeTokens]
 * refuses to materialise a token-only section, and [storeTokensForAccount] refuses to write tokens
 * for an account the vault no longer holds. Without that, a token refresh whose relay round-trip
 * overlapped a [clearAccount] restored live bearer credentials for the retired account.
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
        DecoySectionLock.withSection(runtime) {
            // Tokens belong TO an account. Writing them onto a vault that holds none would
            // materialise a token-only section — bearer credentials for an account this vault does
            // not claim, and (before U3 wires anything) a `TAG_DECOY` on a vault that never
            // provisioned. Same fail-closed direction as the [accountId] setter above.
            val current = runtime.read { it.decoy?.accountId } ?: return@withSection
            writeTokensLocked(current, access, refresh)
        }
    }

    /**
     * Store tokens **only while the account is still [accountId]**, and report whether they were.
     * **[R3]**
     *
     * The one caller — `DecoyAccountProvisioner.refreshTokens` — reads the account, blocks on the
     * relay for as long as a login takes, and writes when the answer arrives. The section lock
     * cannot be held across that (it would stall the send path behind a network round-trip), so the
     * write must instead be conditional on what is true when it runs: a [clearAccount] that landed
     * in the window means those tokens are for a retired account, and writing them would restore
     * live bearer credentials the vault has just given up. A retired account whose credentials come
     * back is not retired.
     *
     * The read and the write are one sequence under the section monitor, so no other writer of the
     * section can land between them — the same rule the provisioner's commit-and-revert follows.
     */
    fun storeTokensForAccount(accountId: String, access: String, refresh: String): Boolean =
        DecoySectionLock.withSection(runtime) {
            if (runtime.read { it.decoy?.accountId } != accountId) return@withSection false
            writeTokensLocked(accountId, access, refresh)
            true
        }

    /** The token write itself. Called only with the section lock held and the account verified. */
    private fun writeTokensLocked(accountId: String, access: String, refresh: String) {
        runtime.mutate {
            // `?: DecoyState()` is unreachable — the caller verified an account id under this same
            // lock — and is kept only so the copy-with has a receiver.
            it.decoy = (it.decoy ?: DecoyState(accountId = accountId))
                .copy(accessToken = access, refreshToken = refresh)
        }
    }

    override fun clearTokens() {
        DecoySectionLock.withSection(runtime) {
            runtime.mutate {
                // Only rewrite when a holder already exists: clearing tokens on a vault that has no
                // cover-traffic state must not CREATE the section. An empty section is omitted by
                // the codec anyway, but not materialising it keeps the intent explicit.
                it.decoy?.let { current ->
                    it.decoy = current.copy(accessToken = null, refreshToken = null)
                }
            }
        }
    }

    override fun clearAccount() {
        DecoySectionLock.withSection(runtime) {
            runtime.mutate {
                // Drop the whole credential set together, mirroring how it was committed: an
                // account id and its identity key are never separated in either direction.
                //
                // ⚠️ THE TOKENS GO TOO **[R2]**. Round 1 left them behind, which made "the account
                // was cleared" false in the only sense that matters to an attacker: the access JWT
                // keeps authenticating that account until it expires and the refresh token mints a
                // whole new session from it. A retired account whose live bearer credentials
                // survive is not retired. They are nulled in the SAME mutate as the id and the key,
                // so no generation ever carries a token for an account this vault no longer claims.
                //
                // counterHighWater goes with them, and that is not tidiness. The mark means "every
                // value below this may already have been issued" — a statement about ONE synthetic
                // peer. Carry it across a re-provision and the replacement account's very first
                // envelope arrives at the relay carrying `message_number = 128`, in the clear, on a
                // brand-new account whose session was just established. A real Double Ratchet with
                // a new recipient starts at 0, so a nonzero start is a classifier the relay
                // operator gets for free. Resetting it is safe against a live
                // DecoyCounterReservation because this whole mutate runs under the SECTION lock,
                // so it cannot land between that allocator's staleness check and its spend — the
                // allocator therefore always observes the reset before deciding, abandons its stale
                // block, and reserves fresh.
                it.decoy?.let { current ->
                    current.wipe()
                    it.decoy = current.copy(
                        accountId = null,
                        identityKeyPair = null,
                        accessToken = null,
                        refreshToken = null,
                        counterHighWater = 0L,
                    )
                }
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
