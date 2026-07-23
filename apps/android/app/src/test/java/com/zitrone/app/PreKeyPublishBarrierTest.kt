// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.vault.VaultCapacityException
import com.zitrone.app.crypto.vault.VaultImageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * D2c round 7 PREKEY publish barrier. Before publishing a generated prekey's PUBLIC half (api.register
 * / api.uploadPreKeys) the coordinator reseals the just-stored PRIVATE half DURABLE and publishes ONLY
 * when that confirms — so a crash can never roll the private half back while the relay already serves a
 * bundle whose private half we no longer hold (→ a peer's first X3DH message permanently undecryptable).
 * The barrier IS [flushSendRatchet] routed through the injected flushBeforeAck (via the coordinator's
 * private `flushBeforePreKeyPublish`), the same tested decision the outbound send uses.
 *
 * The full coordinator is not host-drivable (WsClient / ApiClient / SignalProtocolManager are final,
 * the transport is socket-bound), so these drive the exact call-site GLUE each prekey path runs — the
 * top-up / rotate guard `if (flush()) publish()` and the register guard `if (!flush()) throw` — pinning
 * "public halves are NOT published when the private-half reseal is not durable" as a source invariant.
 */
class PreKeyPublishBarrierTest {

    /** The top-up / rotation call sites: `if (flushBeforePreKeyPublish {…}) api.uploadPreKeys(...)`. */
    private suspend fun uploadGuard(flush: suspend () -> Unit, publish: () -> Unit): Boolean {
        var notDurable = false
        if (flushSendRatchet(flush = flush, onNotDurable = { notDurable = true }, backoff = { })) {
            publish()
        }
        return notDurable
    }

    /** The register call site: `if (!flushBeforePreKeyPublish {…}) throw PreKeyFlushNotDurableException()`. */
    private suspend fun registerGuard(flush: suspend () -> Unit, register: () -> Unit) {
        if (!flushSendRatchet(flush = flush, onNotDurable = { }, backoff = { })) {
            throw PreKeyFlushNotDurableException()
        }
        register()
    }

    @Test
    fun `a durable reseal publishes the public halves`() = runTest {
        var published = false
        val notDurable = uploadGuard(flush = { /* durable */ }, publish = { published = true })
        assertTrue("public halves uploaded once the private half is durable", published)
        assertFalse(notDurable)
    }

    @Test
    fun `a non-durable reseal does NOT publish the public halves`() = runTest {
        var published = false
        // NotDurable / IO / capacity / closed all surface as a throw — the private half did NOT reach
        // disk, so the public half must never be uploaded (a later flush that lands then publishes).
        val notDurable = uploadGuard(
            flush = { throw VaultImageException.NotDurable() },
            publish = { published = true },
        )
        assertFalse("public halves must NOT be uploaded when the private half is not durable", published)
        assertTrue("the barrier diag'd the skipped upload", notDurable)
    }

    @Test
    fun `a full-vault reseal does NOT publish (fail-closed, no retry)`() = runTest {
        var published = false
        uploadGuard(flush = { throw VaultCapacityException("vault full") }, publish = { published = true })
        assertFalse("capacity fails closed — no publish", published)
    }

    @Test
    fun `the register path THROWS PreKeyFlushNotDurableException and never registers on a non-durable reseal`() {
        var registered = false
        assertThrows(PreKeyFlushNotDurableException::class.java) {
            runBlocking {
                registerGuard(flush = { throw IOException("reseal not durable") }, register = { registered = true })
            }
        }
        assertFalse("register must not fire when the identity/prekey reseal is not durable", registered)
    }

    @Test
    fun `the register path registers when the reseal is durable`() = runTest {
        var registered = false
        registerGuard(flush = { /* durable */ }, register = { registered = true })
        assertTrue("a durable reseal proceeds to register", registered)
    }

    @Test
    fun `a CancellationException from the reseal propagates and never publishes`() {
        var published = false
        // Cooperative cancellation (a teardown mid-boot) must unwind, not be folded into a not-durable
        // false that silently skips the publish — flushSendRatchet rethrows it before onNotDurable.
        assertThrows(CancellationException::class.java) {
            runBlocking {
                uploadGuard(flush = { throw CancellationException("boot cancelled") }, publish = { published = true })
            }
        }
        assertFalse("cancellation never publishes", published)
    }
}
