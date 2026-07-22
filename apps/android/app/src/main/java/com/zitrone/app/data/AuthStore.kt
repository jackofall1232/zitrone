// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

package com.zitrone.app.data

import android.content.SharedPreferences
import com.zitrone.app.crypto.KeyStoreManager
import com.zitrone.app.crypto.vault.VaultRuntime

/**
 * The account id + session tokens as they live inside a sealed vault. Immutable data
 * class, swapped wholesale inside a [VaultRuntime.mutate] block (never field-mutated).
 *
 * The three fields are JVM `String`s — immutable and therefore UN-WIPEABLE (they can
 * linger in heap until GC). This is the SAME accepted, documented tradeoff the passphrase
 * path carries (see KeySlot.kt's `KeyDeriver` note): the tokens are short-lived (the
 * access token is a 15-minute JWT; the refresh token rotates on every use), so the residue
 * window is small, and moving auth to `CharArray` would ripple through the whole HTTP stack
 * for little gain. The high-value, long-lived secrets (the Signal identity/ratchet records)
 * ARE wiped by [com.zitrone.app.crypto.vault.VaultState.wipe].
 */
data class AuthState(
    val accountId: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
)

/**
 * The account/token surface [com.zitrone.app.net.ApiClient] needs, behind an interface so
 * PR-D can swap ApiClient's EncryptedSharedPreferences persistence for the vault without
 * touching the client's endpoint logic. Mirrors the exact shape ApiClient uses today:
 * an [accountId] get/set, read-only [accessToken] / [refreshToken], a paired
 * [storeTokens], a token-only [clearTokens] (logout / 401), and an account-only
 * [clearAccount] (full delete).
 */
interface AuthStore {
    /** The registered account id, or null before registration. Settable (registration writes it). */
    var accountId: String?

    /** The current access JWT, or null when logged out. */
    val accessToken: String?

    /** The current (single-use, rotated) refresh token, or null when logged out. */
    val refreshToken: String?

    /** Store a freshly issued access+refresh pair (login / refresh). */
    fun storeTokens(access: String, refresh: String)

    /** Drop both tokens (logout / a 401), leaving the account id intact. */
    fun clearTokens()

    /** Drop the account id (full account deletion; caller also clears tokens). */
    fun clearAccount()
}

/**
 * [AuthStore] over EncryptedSharedPreferences — the LEGACY persistence
 * [com.zitrone.app.net.ApiClient] used inline before PR-D2a lifted it behind the
 * interface. The read/write logic is verbatim the client's old `authPrefs`
 * accessors: the SAME PREFS_AUTH file, the SAME `account_id` / `access_token` /
 * `refresh_token` keys, the SAME `apply()` (non-forced) persistence and the SAME
 * remove-on-clear semantics — so wiring this in [SessionContainer] is
 * byte-for-byte identical to the pre-refactor behaviour.
 *
 * The [prefs] constructor is the seam under test; the [KeyStoreManager]
 * convenience constructor is what production wires (matching the old
 * `keyStoreManager.prefs(PREFS_AUTH)` handle exactly).
 */
class EncryptedAuthStore(private val prefs: SharedPreferences) : AuthStore {

    constructor(keyStoreManager: KeyStoreManager) :
        this(keyStoreManager.prefs(KeyStoreManager.PREFS_AUTH))

    override var accountId: String?
        get() = prefs.getString(KEY_ACCOUNT_ID, null)
        set(value) {
            // null means ABSENT: EncryptedSharedPreferences diverges from the
            // platform putString(key, null)==remove contract by persisting an
            // encrypted "__NULL__" sentinel (leaving contains() true), so remove
            // explicitly. No production caller passes null today (register
            // stores a non-null id; deletion goes through clearAccount()).
            if (value == null) {
                prefs.edit().remove(KEY_ACCOUNT_ID).apply()
            } else {
                prefs.edit().putString(KEY_ACCOUNT_ID, value).apply()
            }
        }

    override val accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)

    override val refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)

    override fun storeTokens(access: String, refresh: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, access)
            .putString(KEY_REFRESH_TOKEN, refresh)
            .apply()
    }

    override fun clearTokens() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).apply()
    }

    override fun clearAccount() {
        prefs.edit().remove(KEY_ACCOUNT_ID).apply()
    }

    companion object {
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}

/**
 * [AuthStore] over a sealed vault via [VaultRuntime]. Every read/write goes through the
 * runtime's single lock, so it is SAFE FROM ANY THREAD — including the WsClient callback
 * thread that calls [clearTokens] on a forced disconnect today, concurrently with a
 * foreground login mutate. The runtime serializes them; a reader never sees a torn
 * account/token pair.
 *
 * All writes are COALESCED (non-forced), matching today's `apply()` persistence for token
 * storage — tokens are recoverable by re-login, so they do not need flush-before-ack.
 *
 * Isolated unit: ApiClient is NOT switched to it until PR-D.
 */
class VaultAuthStore(
    private val runtime: VaultRuntime,
) : AuthStore {

    override var accountId: String?
        get() = runtime.read { it.auth.accountId }
        set(value) {
            runtime.mutate { it.auth = it.auth.copy(accountId = value) }
        }

    override val accessToken: String?
        get() = runtime.read { it.auth.accessToken }

    override val refreshToken: String?
        get() = runtime.read { it.auth.refreshToken }

    override fun storeTokens(access: String, refresh: String) {
        runtime.mutate { it.auth = it.auth.copy(accessToken = access, refreshToken = refresh) }
    }

    override fun clearTokens() {
        runtime.mutate { it.auth = it.auth.copy(accessToken = null, refreshToken = null) }
    }

    override fun clearAccount() {
        runtime.mutate { it.auth = it.auth.copy(accountId = null) }
    }
}
