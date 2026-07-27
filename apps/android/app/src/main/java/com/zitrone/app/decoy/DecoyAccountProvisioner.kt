// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.decoy

import com.zitrone.app.crypto.vault.DecoyState
import com.zitrone.app.crypto.vault.VaultCapacityException
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
 * the credential set is committed as ONE [VaultRuntime.mutate] made durable by ONE
 * [VaultRuntime.flushBeforeAck] before this reports success.** Every interruption therefore
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
 * ## `mutate` is not durable — `flushBeforeAck` is
 *
 * [VaultRuntime.mutate] encodes the state and **schedules** a reseal; the write lands later, when
 * the coalescing ceiling fires. Everything here whose correctness depends on surviving process
 * death therefore mutates AND flushes, and **treats the flush's throw as "it never happened"**:
 *
 *  - the credential commit — a `true` return means the credentials are on disk, not merely in RAM,
 *    so a caller that sends cover traffic on the strength of it cannot be using credentials a crash
 *    is about to erase (which would leave the account orphaned and spend a second registration);
 *  - the back-off after a 429 or a capacity failure — "back off ACROSS sessions" is a durability
 *    claim; a scheduled-only deferral is lost by the very crash it must survive, and the next
 *    unlock walks straight back into the shared global bucket.
 *
 * Tokens are the deliberate exception, exactly as [com.zitrone.app.data.VaultAuthStore]'s are: they
 * are re-mintable from the stored identity key, so a coalesced write is correct for them.
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
 *  2. **One RELAY attempt per session, ever.** [attempted] is a latch, not a counter — a failure is
 *     not retried inside the session, so no tight loop is expressible. It is taken immediately
 *     before the relay sequence and never by a purely local refusal: a back-off window that expires
 *     mid-session must still allow the one attempt, because the latch is one *attempt*, not one
 *     *check*.
 *  3. **A 429 backs off ACROSS sessions**, durably, for a randomized [MIN_BACKOFF_MS] to
 *     [MIN_BACKOFF_MS] + [BACKOFF_JITTER_MS] window. A 429 is contention with other users, not a
 *     client fault, and jitter keeps deferred clients from retrying in lockstep. **A vault that
 *     cannot STORE the account backs off the same way** — otherwise a vault sitting near
 *     `MAX_PAYLOAD_CONTENT_BYTES` registers a fresh account on every single unlock and discards it,
 *     which is systematic, unbounded spend against that one bucket rather than an accepted one-off
 *     orphan.
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

    /** One RELAY attempt per session — see rule 2 in the class kdoc. */
    private val attempted = AtomicBoolean(false)

    /**
     * Whether this vault already holds a usable synthetic account **that was actually recorded**.
     *
     * Presence of the credential pair in the LIVE state is not enough. When a `mutate` overflows
     * the fixed region, [VaultRuntime] retains the mutation in memory, does NOT schedule it, and
     * sets [VaultRuntime.capacityExceeded]; the live state then shows credentials that no reader
     * will ever find on disk and that [VaultRuntime.flushBeforeAck] refuses to confirm. Reporting
     * "provisioned" for those is a readiness lie, so the flag is consulted here.
     *
     * The flag is runtime-wide, so a capacity overflow caused by an UNRELATED write also makes this
     * report false while genuinely durable credentials sit in the section. That is deliberate and
     * conservative in the right direction: while the flag is set nothing decoy-related can be made
     * durable anyway (the counter reservation's flush would refuse), so the honest answer for the
     * session is "no cover traffic", and it becomes true again on the next successful mutate.
     *
     * Read AFTER the state read, so a capacity failure that lands concurrently is still seen.
     */
    fun isProvisioned(): Boolean =
        runtime.read { it.decoy?.isProvisioned == true } && !runtime.capacityExceeded

    /**
     * Ensure this vault has a synthetic account, registering one if it does not.
     *
     * Returns true when the vault holds **durable** usable credentials after the call. **Never
     * throws** except to propagate cancellation; every other outcome — offline, 429, a relay error,
     * a proof-of-work failure, a vault at capacity — returns false and means "no cover traffic this
     * session".
     *
     * Idempotent and cheap when already provisioned. When not, at most one RELAY attempt is made
     * per instance, i.e. once per unlocked session. A purely local refusal (a back-off window still
     * in force) does not consume that attempt: the latch is one *attempt*, not one *check*, and a
     * window that expires mid-session must not force the vault to wait for the next unlock.
     */
    suspend fun provisionIfNeeded(): Boolean {
        if (isProvisioned()) return true
        // Local, no relay contact, no durable write — checked BEFORE the latch so it cannot burn it.
        if (isDeferred()) return false
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
        val identity = DecoyIdentity.generateIdentity()
        // The section as it stands BEFORE the commit — what a capacity failure must restore, since
        // VaultRuntime cannot revert an arbitrary block itself.
        val previous = runtime.read { it.decoy }
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
                powSolver.solve(it, DecoyIdentity.publicKeyBytes(identity.identityKeyPair))
            }

            // ── the relay commit. Everything above this line is local and free to abandon. ──
            // The prekey bundle is generated HERE, after the (seconds-long) solve, so its
            // un-zeroable private halves are resident for the register call and not before it.
            val accountId = relay.register(DecoyIdentity.generateBundle(identity), powProof)
            val tokens = relay.createSession(accountId) { challenge ->
                DecoyIdentity.signLoginChallenge(identity.identityKeyPair, challenge)
            }

            // ── the durable commit: ONE mutate, the whole credential set, never a part of it ──
            runtime.mutate { state ->
                state.decoy = (state.decoy ?: DecoyState()).copy(
                    accountId = accountId,
                    identityKeyPair = identity.identityKeyPair,
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    // A successful provision retires any deferral this vault was carrying.
                    provisionNotBeforeMs = null,
                )
                handedOff = true
            }
            // …and ONE flush. `mutate` only scheduled it; a registration was just spent from a
            // global bucket, so reporting success on bytes that a crash inside the coalescing
            // window would erase is exactly the readiness lie this must not tell. A throw here
            // means "not this session": the credentials stay live and scheduled (the identity key
            // is NOT wiped — the state owns it), a later flush or close still lands them, and the
            // next session finds them and does not re-register.
            runtime.flushBeforeAck()
            return true
        } catch (c: CancellationException) {
            if (!handedOff) wipe(identity.identityKeyPair)
            throw c
        } catch (t: Throwable) {
            when {
                // The credentials do not fit. VaultRuntime has RETAINED them unscheduled and set
                // capacityExceeded — which fail-closes flushBeforeAck for the WHOLE vault, real
                // messages included. Put the section back the way it was (that state fits, so the
                // re-encode clears the flag) and record a durable back-off in the same mutate:
                // without one, every unlock of a near-capacity vault registers another account
                // against the shared global bucket and then throws it away.
                t is VaultCapacityException -> if (revertAndDefer(previous)) handedOff = false
                t is ApiClient.ApiException && t.code == HTTP_TOO_MANY_REQUESTS -> deferProvisioning()
            }
            if (!handedOff) wipe(identity.identityKeyPair)
            return false
        }
    }

    /**
     * Restore the decoy section to [previous] and record a durable back-off, after a commit that
     * could not fit.
     *
     * Returns whether the live state was successfully restored — i.e. whether it has let go of the
     * identity key array, which is what tells the caller it may wipe it.
     *
     * Two-step by necessity. The retained over-capacity mutation is still in the live state, so a
     * deferral written on top of it would re-encode the same over-capacity state and overflow
     * again; the revert has to be part of the same block. And if even `previous` + a deferral no
     * longer fits (a vault that was already at the boundary), a bare revert is attempted anyway,
     * because leaving `capacityExceeded` set would block flush-before-ack for the inbound message
     * path — a cover-traffic failure must never degrade the real one.
     */
    private fun revertAndDefer(previous: DecoyState?): Boolean {
        val notBefore = backoffDeadline()
        val restored = try {
            runtime.mutate { state ->
                state.decoy = (previous ?: DecoyState()).copy(provisionNotBeforeMs = notBefore)
            }
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            try {
                runtime.mutate { state -> state.decoy = previous }
                true
            } catch (c: CancellationException) {
                throw c
            } catch (t2: Throwable) {
                // Silent by requirement. The live state still holds the mutation, so the caller
                // must NOT wipe the key it references.
                false
            }
        }
        if (restored) flushBestEffort()
        return restored
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
     *
     * **Mutate then flush.** "Across sessions" is a durability claim, and `mutate` alone only
     * schedules: a crash inside the coalescing window loses the deferral, and the next unlock
     * re-hits a bucket that is shared by every client worldwide. The flush is what makes the
     * back-off mean what it says.
     */
    private fun deferProvisioning() {
        val notBefore = backoffDeadline()
        try {
            runtime.mutate { state ->
                state.decoy = (state.decoy ?: DecoyState()).copy(provisionNotBeforeMs = notBefore)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Silent by requirement. Nothing was recorded, so there is nothing to flush.
            return
        }
        flushBestEffort()
    }

    /** A jittered back-off deadline — see [MIN_BACKOFF_MS] / [BACKOFF_JITTER_MS]. */
    private fun backoffDeadline(): Long =
        clock() + MIN_BACKOFF_MS + (random.nextDouble() * BACKOFF_JITTER_MS).toLong()

    /**
     * Make whatever was just scheduled durable, swallowing failure.
     *
     * The swallow is correct HERE and nowhere else in this file: the value being flushed is a
     * back-off, and a lost back-off costs at most one extra registration attempt next session,
     * whereas throwing would break the never-throws contract. It is not correct for the credential
     * commit, which reports readiness — that one propagates into a `false` return.
     */
    private fun flushBestEffort() {
        try {
            runtime.flushBeforeAck()
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
