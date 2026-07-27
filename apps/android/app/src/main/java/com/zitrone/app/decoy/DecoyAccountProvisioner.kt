// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.decoy

import com.zitrone.app.crypto.vault.DecoyState
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.wipe
import com.zitrone.app.data.DecoyAuthStore
import com.zitrone.app.net.ApiClient
import kotlinx.coroutines.CancellationException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provisions — lazily, once, per vault — the synthetic relay account that vault addresses its
 * cover traffic to, and keeps that account's session tokens fresh.
 *
 * ## Ordering, which is the whole correctness argument
 *
 * **The account is registered on the relay BEFORE its credentials are committed to the vault, and
 * the credential set is committed as ONE [VaultRuntime.mutate].** Every interruption therefore
 * lands on one of two acceptable outcomes:
 *
 *  - an **orphaned relay account** — registered, never referenced, never used. Harmless: an
 *    unused account holding nothing but public keys, which the relay's own TTLs outlive; or
 *  - a **complete credential set** — account id, identity keypair and tokens together.
 *
 * The outcome it structurally cannot produce is a vault referencing an account whose signing key
 * was never persisted, which would be unauthenticatable, undeletable, and would break every
 * subsequent decoy send. That is why provisioning runs its [DecoyRelayApi] against a RAM-only
 * staging store rather than the vault (see [ApiClientDecoyRelay]), and why [DecoyAuthStore]'s
 * account-id setter is fail-closed.
 *
 * ## Registration is a scarce SHARED GLOBAL resource
 *
 * The relay's registration limiter is keyed on the proxy's socket address, so it is **one bucket
 * shared by every client worldwide** — clearnet and every Tor/I2P client alike. A synthetic
 * account therefore does not spend this device's headroom, it spends everyone's. Three rules
 * follow, and all three are enforced here rather than left to callers:
 *
 *  1. **Lazy.** Nothing is provisioned at vault creation. [provisionIfNeeded] is called from the
 *     first session that actually needs cover traffic; a vault that never sends never registers.
 *  2. **One attempt per session, ever.** [attempted] is a latch, not a counter — a failure is not
 *     retried inside the session, so no tight loop is expressible.
 *  3. **A 429 backs off ACROSS sessions**, durably, for a randomized [MIN_BACKOFF_MS] to
 *     [MIN_BACKOFF_MS] + [BACKOFF_JITTER_MS] window. A 429 is contention with other users, not a
 *     client fault, and jitter keeps deferred clients from retrying in lockstep.
 *
 * ## Failure degrades SILENTLY to cover-traffic-off
 *
 * No public method here throws (other than propagating [CancellationException] so structured
 * cancellation still unwinds). A failure returns `false`, which means "no cover traffic this
 * session" and nothing else: onboarding is never blocked, no dialog is shown, no error implying a
 * fault is surfaced — and, per the vault-count-oracle rule, **nothing is logged or recorded to any
 * device-level diagnostics sink.** This class takes no logger and no diagnostics handle, so that
 * is structural rather than a matter of discipline.
 *
 * ## Lifetime
 *
 * One instance per live session, constructed from that session's [VaultRuntime] — never a
 * device-global singleton. It owns no timers and no background job: it is `suspend` throughout, so
 * cancelling the session scope is the whole teardown.
 */
class DecoyAccountProvisioner(
    private val runtime: VaultRuntime,
    private val relay: DecoyRelayApi,
    private val powSolver: DecoyPowSolver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: java.util.Random = SecureRandom(),
) {

    /** One provisioning attempt per session — see rule 2 in the class kdoc. */
    private val attempted = AtomicBoolean(false)

    /** Whether this vault already holds a usable synthetic account. */
    fun isProvisioned(): Boolean = runtime.read { it.decoy?.isProvisioned == true }

    /**
     * Ensure this vault has a synthetic account, registering one if it does not.
     *
     * Returns true when the vault holds usable credentials after the call. **Never throws** except
     * to propagate cancellation; every other outcome — offline, 429, a relay error, a proof-of-work
     * failure, a vault at capacity — returns false and means "no cover traffic this session".
     *
     * Idempotent and cheap when already provisioned. When not, the attempt is made at most once
     * per instance, i.e. once per unlocked session.
     */
    suspend fun provisionIfNeeded(): Boolean {
        if (isProvisioned()) return true
        if (!attempted.compareAndSet(false, true)) return false
        return try {
            provision()
        } catch (c: CancellationException) {
            // Cooperative cancellation still unwinds — the package-wide catch-ordering discipline.
            throw c
        } catch (t: Throwable) {
            // Silent by requirement. Not logged, not recorded, not surfaced.
            false
        }
    }

    /**
     * Re-mint or refresh the synthetic account's session tokens (the refresh token's TTL is 7
     * days, so a vault left unopened longer than that always needs a fresh login).
     *
     * Tries the rotating refresh token first, then falls back to a full challenge-signature login
     * with the stored identity key — which always works, because possession of that key IS the
     * account. Returns whether tokens were obtained and stored. Never throws except to propagate
     * cancellation, and never touches anything but the token fields.
     */
    suspend fun refreshTokens(): Boolean {
        val credentials = readCredentials() ?: return false
        return try {
            val refreshed = credentials.refreshToken?.let {
                try {
                    relay.refreshSession(it)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // An expired or already-rotated refresh token is the expected case after a
                    // long lock, not an error — fall through to a full login.
                    null
                }
            }
            val tokens = refreshed ?: relay.createSession(credentials.accountId) { challenge ->
                DecoyIdentity.signLoginChallenge(credentials.identityKeyPair, challenge)
            }
            DecoyAuthStore(runtime).storeTokens(tokens.accessToken, tokens.refreshToken)
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            false
        } finally {
            // The snapshot's copy of the identity PRIVATE key dies with this call, on every path.
            wipe(credentials.identityKeyPair)
        }
    }

    // ── provisioning ────────────────────────────────────────────────────────────

    private suspend fun provision(): Boolean {
        if (isDeferred()) return false

        val material = DecoyIdentity.generate()
        // Set INSIDE the mutate block, so it is true whenever the live state has taken ownership of
        // the key array — including when the encode AFTER the block throws (VaultRuntime retains an
        // over-capacity mutation in memory). Wiping an array the live state holds would leave the
        // vault carrying a zeroed identity key, which is worse than the leak the wipe prevents.
        var handedOff = false
        try {
            // Same order as an ordinary boot: challenge → solve → register → session. A null
            // challenge means the relay has no PoW endpoint, so register without a proof.
            val challengeToken = relay.registrationChallenge()
            val powProof = challengeToken?.let {
                powSolver.solve(it, DecoyIdentity.publicKeyBytes(material.identityKeyPair))
            }

            // ── the relay commit. Everything above this line is local and free to abandon. ──
            val accountId = relay.register(material, powProof)
            val tokens = relay.createSession(accountId) { challenge ->
                DecoyIdentity.signLoginChallenge(material.identityKeyPair, challenge)
            }

            // ── the durable commit: ONE mutate, the whole credential set, never a part of it ──
            runtime.mutate { state ->
                state.decoy = (state.decoy ?: DecoyState()).copy(
                    accountId = accountId,
                    identityKeyPair = material.identityKeyPair,
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    // A successful provision retires any deferral this vault was carrying.
                    provisionNotBeforeMs = null,
                )
                handedOff = true
            }
            return true
        } catch (c: CancellationException) {
            if (!handedOff) wipe(material.identityKeyPair)
            throw c
        } catch (t: Throwable) {
            if (!handedOff) wipe(material.identityKeyPair)
            if (t is ApiClient.ApiException && t.code == HTTP_TOO_MANY_REQUESTS) deferProvisioning()
            return false
        }
    }

    /** True while a durable 429 back-off is still in force. */
    private fun isDeferred(): Boolean {
        val notBefore = runtime.read { it.decoy?.provisionNotBeforeMs } ?: return false
        val now = clock()
        // A deferral further out than the longest one this code can write is not a deferral we
        // wrote — it is a clock that moved. Treat it as expired rather than deferring forever.
        if (notBefore - now > MIN_BACKOFF_MS + BACKOFF_JITTER_MS) return false
        return now < notBefore
    }

    /**
     * Persist the cross-session back-off. Best-effort: a vault that cannot take this write is a
     * vault that will simply try again next session, which is strictly less bad than throwing out
     * of a path whose entire contract is that it stays silent.
     */
    private fun deferProvisioning() {
        val notBefore = clock() + MIN_BACKOFF_MS + (random.nextDouble() * BACKOFF_JITTER_MS).toLong()
        try {
            runtime.mutate { state ->
                state.decoy = (state.decoy ?: DecoyState()).copy(provisionNotBeforeMs = notBefore)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Silent by requirement.
        }
    }

    // ── credential reads ────────────────────────────────────────────────────────

    /**
     * A wiped-after-use snapshot of the synthetic credentials.
     *
     * The identity keypair is COPIED out under the runtime lock rather than used by reference: the
     * live array can be zeroed by [com.zitrone.app.crypto.vault.VaultState.wipe] at any moment
     * (session close races an in-flight token refresh), and signing with a half-zeroed key would
     * be an unexplainable login failure. The copy is wiped in a `finally` on every path.
     */
    private class Credentials(
        val accountId: String,
        val identityKeyPair: ByteArray,
        val refreshToken: String?,
    )

    private fun readCredentials(): Credentials? = runtime.read { state ->
        val decoy = state.decoy ?: return@read null
        val accountId = decoy.accountId ?: return@read null
        val identity = decoy.identityKeyPair ?: return@read null
        Credentials(accountId, identity.copyOf(), decoy.refreshToken)
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /**
         * Floor of the 429 back-off. The relay's registration limiter uses a one-hour window, so
         * retrying sooner cannot succeed against a bucket that is genuinely full.
         */
        const val MIN_BACKOFF_MS: Long = 60L * 60 * 1000

        /**
         * Uniform jitter added to [MIN_BACKOFF_MS]. The bucket is global, so every rate-limited
         * client is rate-limited at the same instant; retrying on a fixed delay would rebuild the
         * same stampede an hour later.
         */
        const val BACKOFF_JITTER_MS: Long = 30L * 60 * 1000
    }
}
