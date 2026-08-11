// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.net.registry.ManifestVerifier
import com.zitrone.app.net.registry.RegistryResolver
import com.zitrone.app.net.registry.RegistrySnapshotStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [RegistryResolver] source ordering, the DEFINED success predicate, the pin
 * fail-closed gate, rollback protection, and the cache-feeding refresh — over the
 * REAL verifier with REAL signatures ([RegistryTestSigner]) and the real
 * [RegistrySnapshotStore] over [FakeSharedPreferences], so the epoch high-water
 * behavior under test is the production one, not a mock's.
 */
class RegistryResolverTest {

    private val now = Instant.parse("2026-08-15T00:00:00Z").toEpochMilli()
    private val verifier = ManifestVerifier(RegistryTestSigner.ed25519Verify, nowMs = { now })
    private val prefs = FakeSharedPreferences()
    private val snapshots = RegistrySnapshotStore(prefs)

    private fun resolver(
        trustRoot: String = RegistryTestSigner.trustRoot,
        bootstrap: ByteArray? = null,
    ) = RegistryResolver(
        trustRootB64Url = trustRoot,
        verifier = verifier,
        snapshots = snapshots,
        bootstrap = { bootstrap },
        pinnedClearnetHost = "relay.sublemonable.com",
    )

    private fun relaysJson(id: String, host: String = "relay.sublemonable.com") =
        """[{"id":"$id","clearnet":{"apiBaseUrl":"https://$host",""" +
            """"wsUrl":"wss://$host/ws"},"onion":null,"i2p":{"dest":"$id.b32.i2p"}}]"""

    @Test
    fun `bootstrap resolves on a fresh install`() {
        val relay = resolver(bootstrap = RegistryTestSigner.envelope()).resolveLocalRelay()
        assertNotNull(relay)
        assertEquals("relay-cx23", relay!!.id)
        assertEquals("https://relay.sublemonable.com", relay.clearnetApiBaseUrl)
    }

    @Test
    fun `empty trust root disables everything`() {
        assertNull(resolver(trustRoot = "", bootstrap = RegistryTestSigner.envelope()).resolveLocalRelay())
    }

    @Test
    fun `no bootstrap and no cache resolves nothing`() {
        assertNull(resolver(bootstrap = null).resolveLocalRelay())
    }

    @Test
    fun `cache serves when the bootstrap is absent`() {
        val cached = RegistryTestSigner.envelope(relaysJson = relaysJson("relay-cached"))
        assertTrue(snapshots.store(cached, 1))
        val relay = resolver(bootstrap = null).resolveLocalRelay()
        assertEquals("relay-cached", relay?.id)
    }

    @Test
    fun `tampered bootstrap falls through to the cache`() {
        val bad = RegistryTestSigner.envelope().also { it[it.size / 2] = 'X'.code.toByte() }
        val cached = RegistryTestSigner.envelope(relaysJson = relaysJson("relay-cached"))
        assertTrue(snapshots.store(cached, 1))
        assertEquals("relay-cached", resolver(bootstrap = bad).resolveLocalRelay()?.id)
    }

    @Test
    fun `a stale bootstrap cannot roll back past the cached epoch`() {
        // The device has accepted epoch 5; the (validly signed) bootstrap is epoch 1.
        val cached = RegistryTestSigner.envelope(
            epoch = 5,
            previousManifestHash = "prev",
            relaysJson = relaysJson("relay-epoch5"),
        )
        assertTrue(snapshots.store(cached, 5))
        val bootstrap = RegistryTestSigner.envelope(relaysJson = relaysJson("relay-epoch1"))
        assertEquals("relay-epoch5", resolver(bootstrap = bootstrap).resolveLocalRelay()?.id)
    }

    @Test
    fun `corrupted cache degrades to a miss, not acceptance`() {
        prefs.edit().putString("registry_snapshot", "!!!not-base64url-at-all!!!").commit()
        assertNull(resolver(bootstrap = null).resolveLocalRelay())
    }

    @Test
    fun `pin gate skips relays on any other clearnet host, fail closed`() {
        val mixed = """[
            {"id":"relay-evil","clearnet":{"apiBaseUrl":"https://evil.example",
             "wsUrl":"wss://evil.example/ws"},"onion":null,"i2p":null},
            {"id":"relay-good","clearnet":{"apiBaseUrl":"https://relay.sublemonable.com",
             "wsUrl":"wss://relay.sublemonable.com/ws"},"onion":null,"i2p":null}
        ]"""
        val relay = resolver(bootstrap = RegistryTestSigner.envelope(relaysJson = mixed))
            .resolveLocalRelay()
        assertEquals("relay-good", relay?.id)

        val allEvil = resolver(
            bootstrap = RegistryTestSigner.envelope(relaysJson = relaysJson("relay-evil", "evil.example")),
        ).resolveLocalRelay()
        assertNull(allEvil)
    }

    @Test
    fun `pin gate requires BOTH clearnet urls on the pinned host`() {
        val wsElsewhere = """[{"id":"r","clearnet":{"apiBaseUrl":"https://relay.sublemonable.com",
            "wsUrl":"wss://evil.example/ws"},"onion":null,"i2p":null}]"""
        assertNull(
            resolver(bootstrap = RegistryTestSigner.envelope(relaysJson = wsElsewhere))
                .resolveLocalRelay(),
        )
    }

    @Test
    fun `refresh verifies, caches durably, and raises the high-water mark`() = runBlocking {
        val fresh = RegistryTestSigner.envelope(
            epoch = 3,
            previousManifestHash = "prev",
            relaysJson = relaysJson("relay-refreshed"),
        )
        val r = resolver()
        assertTrue(r.refresh(listOf("https://reg.example/m.json")) { fresh })
        assertEquals(3, snapshots.highWaterEpoch())
        // The refreshed manifest is what the NEXT local resolution serves.
        assertEquals("relay-refreshed", r.resolveLocalRelay()?.id)
    }

    @Test
    fun `refresh refuses a rollback below the high-water mark`() = runBlocking {
        assertTrue(snapshots.store(RegistryTestSigner.envelope(epoch = 5, previousManifestHash = "p"), 5))
        val stale = RegistryTestSigner.envelope(epoch = 2, previousManifestHash = "p")
        assertFalse(resolver().refresh(listOf("u")) { stale })
        assertEquals(5, snapshots.highWaterEpoch())
    }

    @Test
    fun `refresh tries sources in order and takes the first that verifies`() = runBlocking {
        val good = RegistryTestSigner.envelope(relaysJson = relaysJson("relay-second-source"))
        val served = mapOf("first" to "garbage".toByteArray(), "second" to good)
        assertTrue(resolver().refresh(listOf("first", "second")) { served[it] })
        assertEquals("relay-second-source", resolver().resolveLocalRelay()?.id)
    }

    @Test
    fun `refresh with unreachable sources or disabled registry reports false`() = runBlocking {
        assertFalse(resolver().refresh(listOf("a", "b")) { null })
        assertFalse(resolver(trustRoot = "").refresh(listOf("a")) { RegistryTestSigner.envelope() })
        assertFalse(resolver().refresh(emptyList()) { RegistryTestSigner.envelope() })
    }

    // ── Rollback floor before the first refresh (blind-review P1) ────────────
    // Every pre-existing rollback test seeded the persisted mark via store()
    // first, so none of them could see the floor sitting at 0 between bootstrap
    // acceptance and the first network refresh. These two do not store() first.

    @Test
    fun `an accepted bootstrap floors the epoch - a stale signed replay is refused`() = runBlocking {
        // Fresh install: the build embeds epoch 5, and NO store() has ever run,
        // so the persisted mark is 0. The bootstrap resolves locally.
        val bootstrap = RegistryTestSigner.envelope(
            epoch = 5,
            previousManifestHash = "prev",
            relaysJson = relaysJson("relay-epoch5"),
        )
        val r = resolver(bootstrap = bootstrap)
        assertEquals("relay-epoch5", r.resolveLocalRelay()?.id)
        // A network-position attacker replays a previously published, validly
        // signed epoch-3 manifest whose window still covers now. It must be
        // refused as a rollback against the BOOTSTRAP's epoch, even though the
        // persisted high-water mark has never been written.
        val stale = RegistryTestSigner.envelope(
            epoch = 3,
            previousManifestHash = "p",
            relaysJson = relaysJson("relay-stale"),
        )
        assertFalse(r.refresh(listOf("https://reg.example/m.json")) { stale })
        // Nothing was persisted: the floor rides the APK, so a fresh install's
        // settings store stays empty (the boot reconciler's fresh-install
        // postcondition) and post-burn stays byte-identical to fresh.
        assertNull(snapshots.snapshotBytes())
        assertEquals(0, snapshots.highWaterEpoch())
        assertEquals("relay-epoch5", r.resolveLocalRelay()?.id)
    }

    @Test
    fun `a manifest newer than the bootstrap epoch refreshes and raises the mark`() = runBlocking {
        val bootstrap = RegistryTestSigner.envelope(
            epoch = 5,
            previousManifestHash = "prev",
            relaysJson = relaysJson("relay-epoch5"),
        )
        val fresh = RegistryTestSigner.envelope(
            epoch = 6,
            previousManifestHash = "prev5",
            relaysJson = relaysJson("relay-epoch6"),
        )
        val r = resolver(bootstrap = bootstrap)
        assertEquals("relay-epoch5", r.resolveLocalRelay()?.id)
        assertTrue(r.refresh(listOf("u")) { fresh })
        assertEquals(6, snapshots.highWaterEpoch())
        assertEquals("relay-epoch6", r.resolveLocalRelay()?.id)
    }
}
