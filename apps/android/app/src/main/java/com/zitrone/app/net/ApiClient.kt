// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.net

import com.zitrone.app.crypto.KeyStoreManager
import com.zitrone.app.crypto.SignalProtocolManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * REST client for the Zitrone server (server.api.endpoints).
 *
 * Auth model: JWT (15 min) + refresh token (7 days, rotated on every use).
 * Login is challenge-based — the client signs
            // TODO(zitrone-cutover): shared wire contract with the live relay — rename only in lockstep with the server.
 * "sublemonable-login:<account_id>:<unix_ts>" with its identity key, so the
 * server never holds a password and never learns anything but a public key.
 *
 * Request/response bodies are JSON. Nothing here is ever logged.
 */
class ApiClient(
    baseUrl: String,
    client: OkHttpClient,
    keyStoreManager: KeyStoreManager,
) {

    // client and baseUrl change together on a transport swap (from AppContainer's
    // apply loop on Dispatchers.Default) and must be read as a CONSISTENT PAIR:
    // request() reads baseUrl to build the URL, execute() reads the client to run
    // it — two reads a mid-flight swap could otherwise straddle, pairing e.g. the
    // I2P SOCKS client with the clearnet https URL (a request that would then
    // fail spuriously). Holding both in one immutable value swapped with a single
    // @Volatile write makes the swap atomic; request() captures one snapshot and
    // rides the matching client along on the Request tag so execute() runs the
    // exact client that built the URL. Over I2P the app dials http://<b32>
    // through the SOCKS-proxied client; over Tor/clearnet the https clearnet host.
    private class Transport(val client: OkHttpClient, val baseUrl: String)

    @Volatile
    private var transport: Transport = Transport(client, baseUrl)

    private val authPrefs = keyStoreManager.prefs(KeyStoreManager.PREFS_AUTH)

    /**
     * Observable mirror of [accountId] so the UI updates the moment
     * registration lands, without depending on some other state re-emitting.
     */
    private val _accountId = MutableStateFlow(authPrefs.getString(KEY_ACCOUNT_ID, null))
    val accountIdFlow: StateFlow<String?> = _accountId.asStateFlow()

    /**
     * [responseBody] is an untrusted, length-capped, single-line-sanitized
     * preview of the server's error payload on a non-2xx response. In
     * practice this server always returns a small `{"error": "<code>"}`
     * validation code (see `errJSON` in internal/api/handlers.go) and never
     * request/response data that could contain user content — but the cap
     * and sanitization below don't assume that; a compromised or fronting
     * server returning something else can't blow up diagnostics or break its
     * single-line log format.
     */
    class ApiException(val code: Int, message: String, val responseBody: String? = null) : IOException(message)

    data class SessionTokens(val accessToken: String, val refreshToken: String)

    /**
     * Swap the OkHttp client and endpoint URL together when the transport
     * changes (I2P b32 vs clearnet host). One @Volatile write, so a concurrent
     * request never sees a half-updated client/URL pair.
     */
    fun updateTransport(newClient: OkHttpClient, newBaseUrl: String) {
        transport = Transport(newClient, newBaseUrl)
    }

    // -- token storage (EncryptedSharedPreferences — never plaintext) ---------

    var accountId: String?
        get() = authPrefs.getString(KEY_ACCOUNT_ID, null)
        private set(value) {
            authPrefs.edit().putString(KEY_ACCOUNT_ID, value).apply()
            _accountId.value = value
        }

    val accessToken: String?
        get() = authPrefs.getString(KEY_ACCESS_TOKEN, null)

    private val refreshToken: String?
        get() = authPrefs.getString(KEY_REFRESH_TOKEN, null)

    private fun storeTokens(tokens: SessionTokens) {
        authPrefs.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .apply()
    }

    fun clearTokens() {
        authPrefs.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).apply()
    }

    // -- endpoints --------------------------------------------------------------

    /**
     * POST /api/v1/register — creates the account. The server receives ONLY
     * public keys; it can never learn anything else about this identity.
     */
    suspend fun register(
        identityKeyBase64: String,
        registrationId: Int,
        signedPreKey: SignalProtocolManager.SignedPreKeyDto,
        oneTimePreKeys: List<SignalProtocolManager.OneTimePreKeyDto>,
    ): String {
        val body = JSONObject().apply {
            put("identity_key", identityKeyBase64)
            put("registration_id", registrationId)
            put("signed_prekey", signedPreKey.toJson())
            put("one_time_prekeys", JSONArray().apply {
                oneTimePreKeys.forEach { put(it.toJson()) }
            })
        }
        val json = execute(post("/api/v1/register", body, authenticated = false))
        val newAccountId = json.getString("account_id")
        accountId = newAccountId
        return newAccountId
    }

    /**
     * POST /api/v1/session — authenticates via an Ed25519/XEdDSA-signed,
     * timestamped challenge: "sublemonable-login:<account_id>:<unix_ts>".
     * The timestamp bounds replay; the server validates a small clock skew.
     */
    suspend fun createSession(signChallenge: (String) -> String): SessionTokens {
        val id = accountId ?: throw ApiException(0, "Not registered")
        val unixTs = System.currentTimeMillis() / 1000L
        val challenge = loginChallenge(id, unixTs)
        val body = JSONObject().apply {
            put("account_id", id)
            put("timestamp", unixTs)
            put("signature", signChallenge(challenge))
        }
        val json = execute(post("/api/v1/session", body, authenticated = false))
        return parseTokens(json).also(::storeTokens)
    }

    /**
     * POST /api/v1/session/refresh — refresh tokens are single-use and
     * rotated on every call (critical rule: ALWAYS rotate refresh tokens).
     */
    suspend fun refreshSession(): SessionTokens {
        val current = refreshToken ?: throw ApiException(401, "No refresh token")
        val body = JSONObject().put("refresh_token", current)
        val json = execute(post("/api/v1/session/refresh", body, authenticated = false))
        return parseTokens(json).also(::storeTokens)
    }

    /** DELETE /api/v1/session — logout, invalidates both tokens. */
    suspend fun deleteSession() {
        try {
            execute(request("/api/v1/session").delete().build())
        } finally {
            clearTokens()
        }
    }

    /**
     * GET /api/v1/users/:id/prekey — fetch a one-time prekey bundle for X3DH.
     * Wire shape (server handlers.go GetPrekeyBundle): identity_key at the top
     * level, nested signed_prekey {id, public_key, signature}, and a nullable
     * nested one_time_prekey {id, public_key}.
     */
    suspend fun fetchPreKeyBundle(userId: String): SignalProtocolManager.PreKeyBundleDto {
        val json = execute(request("/api/v1/users/$userId/prekey").get().build())
        val signedPreKey = json.getJSONObject("signed_prekey")
        val oneTimePreKey = if (json.isNull("one_time_prekey")) null else json.getJSONObject("one_time_prekey")
        return SignalProtocolManager.PreKeyBundleDto(
            // The zero-knowledge server doesn't issue registration IDs (it
            // stores nothing device-identifying); a fixed value satisfies
            // libsignal's addressing in this one-device-per-account design.
            registrationId = 1,
            deviceId = 1,
            identityKeyBase64 = json.getString("identity_key"),
            signedPreKeyId = signedPreKey.getInt("id"),
            signedPreKeyBase64 = signedPreKey.getString("public_key"),
            signedPreKeySignatureBase64 = signedPreKey.getString("signature"),
            preKeyId = oneTimePreKey?.getInt("id"),
            preKeyBase64 = oneTimePreKey?.getString("public_key"),
        )
    }

    /** POST /api/v1/prekeys — upload a fresh batch of one-time prekeys. */
    suspend fun uploadPreKeys(
        oneTimePreKeys: List<SignalProtocolManager.OneTimePreKeyDto>,
        signedPreKey: SignalProtocolManager.SignedPreKeyDto? = null,
    ) {
        val body = JSONObject().apply {
            put("one_time_prekeys", JSONArray().apply {
                oneTimePreKeys.forEach { put(it.toJson()) }
            })
            signedPreKey?.let { put("signed_prekey", it.toJson()) }
        }
        execute(post("/api/v1/prekeys", body))
    }

    /** GET /api/v1/prekeys/count — server-side prekey stock. */
    suspend fun preKeyCount(): Int {
        val json = execute(request("/api/v1/prekeys/count").get().build())
        return json.getInt("count")
    }

    /**
     * POST /api/v1/blobs — deposit an encrypted attachment blob (JWT-auth;
     * upload metadata is no more revealing than message.send). The blob ID is
     * SHA-256(token), so the relay never sees the token until redemption — the
     * same blindness construction as dead drops. A 409 (duplicate blob_id) or
     * any other non-2xx surfaces as an [ApiException] so the send fails cleanly.
     * Both arguments are STANDARD base64 (see crypto/AttachmentCrypto).
     */
    suspend fun uploadBlob(blobIdBase64: String, ciphertextBase64: String) {
        val body = JSONObject().apply {
            put("blob_id", blobIdBase64)
            put("ciphertext", ciphertextBase64)
        }
        execute(post("/api/v1/blobs", body))
    }

    /**
     * POST /api/v1/blobs/redeem — present the token; receive the blob; the blob
     * is destroyed in the same operation (single-use; a replay returns 404). NO
     * authentication: the token is the capability, and an unauthenticated fetch
     * means the relay cannot link a redemption to any account. Returns the
     * ciphertext as STANDARD base64. A 404 (expired or already redeemed) is an
     * [ApiException] with code 404 — callers surface it as "unavailable", not a
     * crash.
     */
    suspend fun redeemBlob(tokenBase64: String): String {
        val body = JSONObject().put("token", tokenBase64)
        val json = execute(post("/api/v1/blobs/redeem", body, authenticated = false))
        return json.getString("ciphertext")
    }

    /**
     * POST /api/v1/qr-drops/fetch — fetch a QR dead-drop ("lemon drop") sealed
     * blob by its qr_id. NO authentication: exactly like [redeemBlob], an
     * unauthenticated fetch means the relay cannot link the fetch to any account
     * — indistinguishable from any other anonymous scanner. [qrId] is the
     * UNPADDED BASE64URL id verbatim from the sticker URL's `/d/{id}` segment
     * (the relay decodes it with RawURLEncoding — see qrdrops.go); it is the sole
     * body field. Fetch is non-destructive on the server, so a wrong-recipient
     * scan can never burn a drop out from under its real recipient.
     *
     * The response ciphertext is deliberately NOT returned: the Android V1 caller
     * fires this ONLY so that a scan is network-indistinguishable from a real
     * redemption attempt and exercises the real route — it never opens the box
     * (see MainActivity). The only information the caller takes is the OUTCOME:
     * success means a live drop's blob was served; a 404 (missing/expired/burned,
     * all indistinguishable) or any other non-2xx surfaces as an [ApiException];
     * consistent with the rest of this client, the CALLER decides what to do —
     * here it maps the result to the advocacy screen's honest copy variants
     * (see classifyLemonDropFetch) and surfaces nothing as an error.
     */
    suspend fun fetchQrDrop(qrId: String) {
        val body = JSONObject().put("qr_id", qrId)
        execute(post("/api/v1/qr-drops/fetch", body, authenticated = false))
    }

    /** DELETE /api/v1/account — full, irreversible purge of all server data. */
    suspend fun deleteAccount() {
        try {
            execute(request("/api/v1/account").delete().build())
        } finally {
            clearTokens()
            authPrefs.edit().remove(KEY_ACCOUNT_ID).apply()
            _accountId.value = null
        }
    }

    // -- plumbing -------------------------------------------------------------------

    private fun parseTokens(json: JSONObject) = SessionTokens(
        accessToken = json.getString("access_token"),
        refreshToken = json.getString("refresh_token"),
    )

    private fun request(path: String, authenticated: Boolean = true): Request.Builder {
        // One snapshot: the URL and the client that will run it must match, so
        // capture both from the same Transport and carry the client on the tag
        // for execute() to read (see the Transport field comment).
        val t = transport
        val builder = Request.Builder()
            .url(t.baseUrl.trimEnd('/') + path)
            .tag(OkHttpClient::class.java, t.client)
        if (authenticated) {
            accessToken?.let { builder.header("Authorization", "Bearer $it") }
        }
        return builder
    }

    private fun post(path: String, body: JSONObject, authenticated: Boolean = true): Request =
        request(path, authenticated)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private suspend fun execute(req: Request): JSONObject =
        suspendCancellableCoroutine { continuation ->
            // Use the client captured alongside this request's URL (request()
            // tagged it), so a transport swap between build and execute can't
            // pair a mismatched client/URL. Fallback covers any request not
            // built via request() — there are none today.
            val call = (req.tag(OkHttpClient::class.java) ?: transport.client).newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            // peekBody, not body.string(): caps how much of a
                            // misbehaving (or malicious) server's response we
                            // ever read into memory, regardless of the
                            // Content-Length it claims. Newlines are stripped
                            // so a multi-line body (e.g. an HTML error page
                            // from a fronting proxy) can't break the
                            // single-line diagnostics log format.
                            val preview = it.peekBody(MAX_ERROR_BODY_BYTES).string()
                                .replace('\n', ' ').replace('\r', ' ')
                                .take(MAX_ERROR_BODY_CHARS).ifBlank { null }
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    ApiException(it.code, "HTTP ${it.code}", preview),
                                )
                            }
                            return
                        }
                        val text = it.body?.string().orEmpty()
                        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                        if (continuation.isActive) continuation.resume(json)
                    }
                }
            })
        }

    private fun SignalProtocolManager.SignedPreKeyDto.toJson() = JSONObject().apply {
        put("id", id)
        put("public_key", publicKeyBase64)
        put("signature", signatureBase64)
        put("created_at", timestampMs)
    }

    private fun SignalProtocolManager.OneTimePreKeyDto.toJson() = JSONObject().apply {
        put("id", id)
        put("public_key", publicKeyBase64)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Defensive cap on the error body surfaced in [ApiException.responseBody]. */
        private const val MAX_ERROR_BODY_CHARS = 200

        /** Bytes peeked from a non-2xx body — bounds memory before the char cap applies. */
        private const val MAX_ERROR_BODY_BYTES = 4096L

        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"

        /**
         * The login challenge string. Pure function — covered by unit tests
         * to keep the client byte-compatible with the server's verifier.
         */
        fun loginChallenge(accountId: String, unixTs: Long): String =
            "sublemonable-login:$accountId:$unixTs"
    }
}
