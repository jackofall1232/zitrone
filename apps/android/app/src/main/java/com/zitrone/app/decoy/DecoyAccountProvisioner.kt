// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.decoy

import com.zitrone.app.crypto.vault.DecoySectionLock
import com.zitrone.app.crypto.vault.DecoyState
import com.zitrone.app.crypto.vault.VaultCapacityException
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.wipe
import com.zitrone.app.data.DecoyAuthStore
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
 *  - the back-off — "back off ACROSS sessions" is a durability claim; a scheduled-only deferral is
 *    lost by the very crash it must survive, and the next unlock walks straight back into the
 *    shared global bucket.
 *
 * Tokens are the deliberate exception, exactly as [com.zitrone.app.data.VaultAuthStore]'s are: they
 * are re-mintable from the stored identity key, so a coalesced write is correct for them.
 *
 * ## Two questions, two predicates — [hasAccount] and [canSend] **[R2]**
 *
 * "Is there a synthetic account?" and "may cover traffic go out?" are different questions and one
 * predicate cannot answer both. Round 1 made a single `isProvisioned()` mean
 * `credentials && !capacityExceeded` and then gated REGISTRATION on it. That is a send predicate
 * used as a register predicate, and review round 2 showed the harm: an UNRELATED write overflowing
 * the region made a vault that already held durable synthetic credentials answer "not provisioned",
 * take the latch, and register a SECOND account — spending the shared worldwide bucket and, if the
 * overflow cleared mid-flight, replacing a perfectly good durable account. Refusing to *send* while
 * the flag is set is right; refusing to *acknowledge an account that already exists* is not.
 *
 *  - [hasAccount] — does a synthetic account exist at all. Consults the section and NOTHING else.
 *    This is what gates registration, so a transient runtime condition can never re-enter the one
 *    path that spends a global resource.
 *  - [canSend] — durable credentials, no capacity overflow, and this session's own credential flush
 *    actually confirmed. This is what gates cover traffic.
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
 *  3. **The back-off is WRITTEN BEFORE the registration is spent, and only a success retires it.**
 *     **[R2]** The deferral is a durable *intent to attempt*, recorded and flushed before any relay
 *     contact; a successful commit clears it in the same mutate that stores the credentials. Two
 *     things fall out, and both were defects when the back-off was written afterwards:
 *      - **A vault that cannot store a deferral never registers at all.** Round 1 wrote the
 *        back-off in the capacity handler, so a vault at ABSOLUTE capacity — where even
 *        `previous + deferral` will not encode — bare-reverted with no back-off on disk and
 *        registered again on the *next unlock*, forever. Writing first inverts that: if the
 *        smallest possible decoy write does not fit, the registration is never spent. There is no
 *        edge left where nothing can be encoded, because nothing has been spent by then.
 *      - **Every failure defers, not just a 429.** A crash between register and commit, an offline
 *        challenge fetch, a dead session mint — all of them leave the deferral standing. That is
 *        deliberate: the bucket is shared by every client worldwide, so the conservative direction
 *        is to make an attempt *cost* a back-off window and let success be the only thing that
 *        clears it. The price is that a vault which failed for a purely local reason waits
 *        [MIN_BACKOFF_MS]–[MIN_BACKOFF_MS] + [BACKOFF_JITTER_MS] before trying again, which for a
 *        background nicety is not a price worth optimising against a global resource.
 *     The window is randomized because the bucket is global — every rate-limited client is limited
 *     at the same instant, and a fixed delay rebuilds the same stampede an hour later.
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
     * True while THIS session's credential commit is live in the state but was never confirmed
     * durable — the window between the commit's `mutate` and its `flushBeforeAck` returning, and
     * permanently afterwards if that flush threw.
     *
     * A flush throw means "it never happened", and round 1 honoured that for the call that saw it
     * (it returns false) but not for the next one: the credentials sit live with `capacityExceeded`
     * clear, so a second readiness check answered "ready" on bytes that no reader will ever find on
     * disk. This is the memory of that failure, and it is exactly session-scoped, which is the
     * right scope: anything decoded from disk at construction is durable by definition, and after
     * a process death the credentials either landed (a later reseal or `close` got them — the next
     * session finds them and does not re-register) or they did not (the next session finds nothing
     * and registers once). Only the session that watched its own flush throw needs to remember.
     *
     * It gates [canSend] and NOT [hasAccount]: an unconfirmed commit is a reason to withhold cover
     * traffic, never a reason to spend a second registration.
     */
    @Volatile
    private var credentialsUnconfirmed: Boolean = false

    /**
     * Whether a synthetic account exists in this vault at all — the REGISTER predicate.
     *
     * Deliberately consults nothing but the section. Registration spends a rate-limit bucket shared
     * by every client worldwide, so the question it gates must be about the vault's durable
     * content and never about a transient runtime condition. Folding
     * [VaultRuntime.capacityExceeded] in here (round 1) made an unrelated overflow re-enter the
     * register path on a vault that already had a good account.
     */
    fun hasAccount(): Boolean = runtime.read { it.decoy?.isProvisioned == true }

    /**
     * Whether cover traffic may go out — the SEND predicate. Three conditions, each for its own
     * failure:
     *
     *  - **[hasAccount]** — there is an account to send as.
     *  - **not [credentialsUnconfirmed]** — this session's own commit was confirmed durable. A
     *    commit whose flush threw is live-but-not-durable; sending on it risks a crash erasing the
     *    credentials while the relay holds an account we can no longer authenticate to.
     *  - **not [VaultRuntime.capacityExceeded]** — the runtime holds an unscheduled mutation, so
     *    `flushBeforeAck` fail-closes for the WHOLE vault. Nothing decoy-related can be made durable
     *    while that is true (the counter reservation's flush would refuse), so the honest answer for
     *    the moment is "no cover traffic". It becomes true again on the next successful mutate, and
     *    it is checked AFTER the state read so a concurrent capacity failure is still seen.
     */
    fun canSend(): Boolean = hasAccount() && !credentialsUnconfirmed && !runtime.capacityExceeded

    /**
     * Ensure this vault has a synthetic account, registering one if it does not.
     *
     * Returns [canSend] — i.e. true when the vault holds **durable** usable credentials and cover
     * traffic may actually go out. **Never throws** except to propagate cancellation; every other
     * outcome — offline, 429, a relay error, a proof-of-work failure, a vault at capacity — returns
     * false and means "no cover traffic this session".
     *
     * Idempotent and cheap when an account already exists: registration is gated on [hasAccount],
     * so a runtime-wide capacity overflow suppresses sending without ever re-entering the register
     * path. When there is no account, at most one RELAY attempt is made per instance, i.e. once per
     * unlocked session. A purely local refusal (a back-off window still in force) does not consume
     * that attempt: the latch is one *attempt*, not one *check*, and a window that expires
     * mid-session must not force the vault to wait for the next unlock.
     */
    suspend fun provisionIfNeeded(): Boolean {
        if (hasAccount()) return canSend()
        // Local, no relay contact, no durable write — checked BEFORE the latch so it cannot burn it.
        if (isDeferred()) return false
        // [R2] The CAS loser reports the truth rather than a flat false: two concurrent callers on
        // one instance used to make the loser answer "no cover traffic" even after the winner had
        // provisioned successfully — a silent decoys-off for the rest of that call chain. This is
        // still racy in the sense that the winner may not have finished yet (there is no waiting
        // here, deliberately — a cover-traffic entry point must not block on a multi-second
        // registration), but it can no longer be a FALSE NEGATIVE once the winner is done.
        if (!attempted.compareAndSet(false, true)) return canSend()
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
        // ── WRITE-AHEAD BACK-OFF, before a single byte of relay contact [R2] ──
        // Rule 3 in the class kdoc. If the smallest decoy write this class can make does not fit,
        // nothing is spent and there is no edge case left to handle at absolute capacity.
        if (!reserveBackoff()) return false

        val identity = DecoyIdentity.generateIdentity()
        // Set INSIDE the mutate block, so it is true whenever the live state has taken ownership of
        // the key array — including when the encode AFTER the block throws (VaultRuntime retains an
        // over-capacity mutation in memory). Wiping an array the live state holds would leave the
        // vault carrying a zeroed identity key, which is worse than the leak the wipe prevents.
        var handedOff = false
        try {
            // Same order as an ordinary boot: challenge → solve → register → session. A null
            // challenge means the relay has no PoW endpoint, so register without a proof.
            // ⚠️ NO LOCK IS HELD HERE. This is seconds of proof-of-work and HTTP; holding the
            // section monitor across it would stall the counter allocator on the send path.
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

            // ── the durable commit, under the SECTION lock from the read through the revert ──
            // `beforeCommit` is read INSIDE the lock and the capacity revert restores it while the
            // lock is still held, so no other writer of the section can interleave between the two.
            // Round 1 snapshotted the section BEFORE the network round-trip above and restored that
            // snapshot seconds later, which clobbered any concurrent decoy write in the window —
            // including a counter reservation, restoring an OLDER high-water mark and reissuing
            // values that had already been handed out. A revert may only ever put back state that
            // was observed under the same lock that the revert itself runs under.
            return DecoySectionLock.withSection(runtime) {
                val beforeCommit = runtime.read { it.decoy }
                // From here the live state may hold credentials that are not yet durable, so no
                // caller may be told it can send until the flush below returns.
                credentialsUnconfirmed = true
                try {
                    // ── ONE mutate, the whole credential set, never a part of it ──
                    runtime.mutate { state ->
                        state.decoy = (state.decoy ?: DecoyState()).copy(
                            accountId = accountId,
                            identityKeyPair = identity.identityKeyPair,
                            accessToken = tokens.accessToken,
                            refreshToken = tokens.refreshToken,
                            // Success is the ONLY thing that retires the write-ahead deferral, and
                            // it does so in the same mutate that stores the credentials.
                            provisionNotBeforeMs = null,
                        )
                        handedOff = true
                    }
                    // …and ONE flush. `mutate` only scheduled it; a registration was just spent
                    // from a global bucket, so reporting success on bytes that a crash inside the
                    // coalescing window would erase is exactly the readiness lie this must not
                    // tell. A throw here means "not this session": the credentials stay live and
                    // scheduled (the identity key is NOT wiped — the state owns it), a later flush
                    // or close still lands them, the next session finds them and does not
                    // re-register, and `credentialsUnconfirmed` keeps THIS session from sending on
                    // them.
                    runtime.flushBeforeAck()
                    credentialsUnconfirmed = false
                    canSend()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // The credentials do not fit. VaultRuntime has RETAINED them unscheduled and
                    // set capacityExceeded — which fail-closes flushBeforeAck for the WHOLE vault,
                    // real messages included. Put the section back exactly as it was read above
                    // (that state fits — it was encoded successfully moments ago under this same
                    // lock — so the re-encode clears the flag), which also restores the write-ahead
                    // deferral this attempt already made durable.
                    if (t is VaultCapacityException && revertSection(beforeCommit)) handedOff = false
                    throw t
                }
            }
        } catch (c: CancellationException) {
            if (!handedOff) wipe(identity.identityKeyPair)
            throw c
        } catch (t: Throwable) {
            if (!handedOff) wipe(identity.identityKeyPair)
            return false
        }
    }

    /**
     * Record the cross-session back-off durably **before** any relay contact, and report whether it
     * is safe to proceed. Rule 3 in the class kdoc.
     *
     * A `false` return means "this vault cannot durably record that it tried", and the correct
     * response is to spend nothing: the alternative is the round-1 behaviour, where a vault too
     * full to hold a deferral registered a fresh account on every unlock and threw it away.
     *
     * The write is `mutate` **and** `flushBeforeAck` — "across sessions" is a durability claim, and
     * a scheduled-only deferral is lost by the very crash it exists to survive. A capacity failure
     * here must be reverted rather than swallowed: an unscheduled mutation leaves
     * [VaultRuntime.capacityExceeded] set, which fail-closes flush-before-ack for the whole vault
     * including the inbound message path, and a cover-traffic write may never degrade the real one.
     */
    private fun reserveBackoff(): Boolean = DecoySectionLock.withSection(runtime) {
        val previous = runtime.read { it.decoy }
        val notBefore = backoffDeadline()
        try {
            runtime.mutate { state ->
                state.decoy = (state.decoy ?: DecoyState()).copy(provisionNotBeforeMs = notBefore)
            }
            runtime.flushBeforeAck()
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Silent by requirement.
            if (t is VaultCapacityException) revertSection(previous)
            false
        }
    }

    /**
     * Put the section back to [previous] after a mutation that could not be encoded.
     *
     * Returns whether the live state let go of the mutation — which, on the credential path, is
     * what tells the caller it may wipe the identity key array.
     *
     * Called only with the section lock held and only with a [previous] that was read under that
     * same lock, so it can never overwrite a concurrent write. **No flush**: [previous] is already
     * the state on disk (nothing between the read and here was ever confirmed durable), so this
     * only needs the re-encode, whose success is what clears [VaultRuntime.capacityExceeded].
     */
    private fun revertSection(previous: DecoyState?): Boolean = try {
        runtime.mutate { state -> state.decoy = previous }
        true
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        // Silent by requirement. The live state still holds the mutation, so a caller holding an
        // identity key the state references must NOT wipe it.
        false
    }

    /** True while a durable back-off is still in force. */
    private fun isDeferred(): Boolean {
        val notBefore = runtime.read { it.decoy?.provisionNotBeforeMs } ?: return false
        val now = clock()
        // A deferral further out than the longest one this code can write is not a deferral we
        // wrote — it is a clock that moved. Treat it as expired rather than deferring forever.
        if (notBefore - now > MIN_BACKOFF_MS + BACKOFF_JITTER_MS) return false
        return now < notBefore
    }

    /** A jittered back-off deadline — see [MIN_BACKOFF_MS] / [BACKOFF_JITTER_MS]. */
    private fun backoffDeadline(): Long =
        clock() + MIN_BACKOFF_MS + (random.nextDouble() * BACKOFF_JITTER_MS).toLong()

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
        /**
         * Floor of the back-off. The relay's registration limiter uses a one-hour window, so
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
