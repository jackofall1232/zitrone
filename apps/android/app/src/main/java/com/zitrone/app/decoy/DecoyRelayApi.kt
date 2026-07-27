// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.decoy

import com.zitrone.app.crypto.LibsodiumRegistrationPowDeriver
import com.zitrone.app.crypto.RegistrationPow
import com.zitrone.app.data.StagingAuthStore
import com.zitrone.app.net.ApiClient
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The relay operations synthetic-account provisioning needs, behind a seam so the provisioner's
 * ordering and failure behaviour are exercisable without a network.
 *
 * Deliberately the SAME endpoints, in the same order, that an ordinary client's boot uses —
 * challenge → solve → register → session — because the point of a synthetic account is that it is
 * a genuinely, ordinarily registered account.
 */
interface DecoyRelayApi {

    /**
     * The registration proof-of-work challenge, or **null when the relay has no such endpoint**
     * (404 — a relay predating the 0.9.4 PoW deploy). Null means "register without a proof",
     * which is exactly what `MessagingCoordinator.bootstrapLoop` does on the same 404.
     */
    suspend fun registrationChallenge(): String?

    /** POST /register. Returns the assigned account id. Throws [ApiClient.ApiException] on 429. */
    suspend fun register(material: DecoyIdentity.Material, powProof: Map<String, String>?): String

    /** POST /session — challenge-signature login for [accountId]. */
    suspend fun createSession(accountId: String, signChallenge: (String) -> String): ApiClient.SessionTokens

    /** POST /session/refresh — refresh tokens are single-use and rotate on every call. */
    suspend fun refreshSession(refreshToken: String): ApiClient.SessionTokens
}

/**
 * Production [DecoyRelayApi]: a real [ApiClient] over the session's transport, wired to a
 * **RAM-only** [StagingAuthStore].
 *
 * The staging store is the load-bearing part. `ApiClient.register()` writes the assigned account
 * id into its store the moment the 201 lands, and `createSession()` writes tokens the moment they
 * are minted. Pointing those at the vault would commit an account id with no identity keypair —
 * a reference this client could never authenticate to. Staging keeps every intermediate in RAM so
 * [DecoyAccountProvisioner] can commit the whole credential set in one durable mutate, and an
 * interruption leaves an orphaned relay account rather than a dangling reference.
 *
 * One instance per provisioning attempt; it holds no durable state and no listener.
 */
class ApiClientDecoyRelay(
    apiBaseUrl: String,
    httpClient: OkHttpClient,
) : DecoyRelayApi {

    private val staging = StagingAuthStore()
    private val api = ApiClient(apiBaseUrl, httpClient, staging)

    override suspend fun registrationChallenge(): String? =
        try {
            api.registrationChallenge()
        } catch (e: ApiClient.ApiException) {
            // 404 = relay predates the PoW deploy entirely; registering proofless is the designed
            // behaviour there (see ApiClient.registrationChallenge's kdoc). Anything else — 429
            // included — is a real failure the provisioner must see.
            if (e.code == 404) null else throw e
        }

    override suspend fun register(material: DecoyIdentity.Material, powProof: Map<String, String>?): String =
        api.register(
            identityKeyBase64 = material.identityKeyBase64,
            registrationId = material.registrationId,
            signedPreKey = material.signedPreKey,
            oneTimePreKeys = material.oneTimePreKeys,
            powProof = powProof,
        )

    override suspend fun createSession(
        accountId: String,
        signChallenge: (String) -> String,
    ): ApiClient.SessionTokens {
        staging.accountId = accountId
        return api.createSession(signChallenge)
    }

    override suspend fun refreshSession(refreshToken: String): ApiClient.SessionTokens {
        // ApiClient reads the refresh token from its store; seed the RAM staging area with it.
        staging.storeTokens(access = "", refresh = refreshToken)
        return api.refreshSession()
    }
}

/** Solves a registration proof-of-work bound to an identity key. See [RegistrationPowSolver]. */
fun interface DecoyPowSolver {
    /** The wire-form proof map, ready to submit with the registration. */
    suspend fun solve(challengeToken: String, identityKeyBytes: ByteArray): Map<String, String>
}

/**
 * Production [DecoyPowSolver] — the same ladder and the same parameters an ordinary registration
 * solves ([RegistrationPow.DEFAULT_PARAMS]), because a synthetic account must cost the network
 * exactly what a real one costs.
 *
 * Two deliberate differences from the ordinary boot path, and both are requirements rather than
 * shortcuts:
 *  - **No progress UI.** Cover-traffic provisioning must never be presented as a second onboarding
 *    wait and must never surface an error implying a fault, so it cannot borrow the pitcher screen.
 *  - **No [com.zitrone.app.diagnostics.RegistrationPowSolveRecorder].** That recorder writes solve
 *    telemetry to the DEVICE-level diagnostics file. Nothing about cover traffic may reach
 *    device-level storage — a device-level record of synthetic-account activity is a vault-count
 *    oracle. This solver therefore runs the raw solver with no sink at all.
 *
 * The solve is pure CPU for seconds, so it runs on [Dispatchers.Default] under [runInterruptible]:
 * cancelling the session scope interrupts the solver thread, which is the solver's only
 * cancellation mechanism.
 */
class RegistrationPowSolver : DecoyPowSolver {

    /** Lazily constructed: libsodium is only touched if a solve actually runs. */
    private val deriver: RegistrationPow.Argon2idDeriver by lazy {
        LibsodiumRegistrationPowDeriver(SodiumAndroid())
    }

    override suspend fun solve(challengeToken: String, identityKeyBytes: ByteArray): Map<String, String> =
        withContext(Dispatchers.Default) {
            runInterruptible {
                RegistrationPow.solve(
                    challengeToken = challengeToken,
                    identityKey = identityKeyBytes,
                    params = RegistrationPow.DEFAULT_PARAMS,
                    deriver = deriver,
                    progress = null,
                ).toJsonMap()
            }
        }
}
