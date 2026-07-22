// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.goterl.lazysodium.SodiumJava
import com.zitrone.app.crypto.vault.LibsodiumVaultOps
import com.zitrone.app.crypto.vault.VAULT_KEY_BYTES
import com.zitrone.app.crypto.vault.VaultCapacityException
import com.zitrone.app.crypto.vault.VaultRuntime
import com.zitrone.app.crypto.vault.VaultSession
import com.zitrone.app.crypto.vault.VaultState
import com.zitrone.app.crypto.vault.VaultStateCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [VaultRuntime] behaviour: the single mutation gate over a [VaultState] + [VaultSession].
 *
 * The runtime's own operations (read / mutate / flushBeforeAck / close) are all SYNCHRONOUS,
 * so these tests need no virtual-time harness — they use a REAL scope with a long cooldown
 * (so the coalescing timer never fires mid-test) and a fake persist sink under the test's
 * control. The AEAD + DEFLATE byte path is real (a mutate genuinely encodes + reseals); only
 * the persist sink is faked so failure and durability are driven deterministically.
 */
class VaultRuntimeTest {

    private val ops = LibsodiumVaultOps(SodiumJava())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A runtime over [state], with a long cooldown so only forced flushes reach [persist]. */
    private fun runtimeOf(
        state: VaultState = VaultState.empty(),
        persist: (Int, ByteArray) -> Unit = { _, _ -> },
    ): VaultRuntime {
        val session = VaultSession(
            scope = scope,
            ops = ops,
            initialPayload = VaultStateCodec.encode(state),
            initialVaultKey = ByteArray(VAULT_KEY_BYTES) { 0x11 },
            slotIndex = 0,
            persist = persist,
            cooldownMs = 60_000L, // real time; the ceiling won't fire during a test
            flushContext = Dispatchers.IO,
        )
        return VaultRuntime(session, state)
    }

    @Test
    fun `mutate applies and read observes - mutate returns the block result`() {
        val runtime = runtimeOf()

        val returned = runtime.mutate {
            it.rosterJson = "roster-v1"
            it.signalRecords["prekey:1"] = byteArrayOf(9, 8, 7)
            123
        }
        assertEquals("mutate returns the block's value", 123, returned)
        assertEquals("read observes the mutation", "roster-v1", runtime.read { it.rosterJson })
        assertTrue(runtime.read { it.signalRecords.containsKey("prekey:1") })
    }

    @Test
    fun `flushBeforeAck forces a durable persist`() {
        val persisted = AtomicInteger(0)
        val runtime = runtimeOf(persist = { _, _ -> persisted.incrementAndGet() })

        runtime.mutate { it.rosterJson = "x" }
        assertEquals("a coalesced mutate has not persisted yet", 0, persisted.get())
        runtime.flushBeforeAck()
        assertEquals("flushBeforeAck persisted synchronously", 1, persisted.get())
    }

    @Test
    fun `flushBeforeAck propagates a failing persist verbatim`() {
        val runtime = runtimeOf(persist = { _, _ -> throw IOException("disk full") })

        runtime.mutate { it.rosterJson = "unacked" }
        // A throw means DO NOT ACK — it must propagate, not be swallowed.
        assertThrows(IOException::class.java) { runtime.flushBeforeAck() }
        // Still dirty: a retry re-attempts (would throw again from this failing sink).
        assertThrows(IOException::class.java) { runtime.flushBeforeAck() }
    }

    @Test
    fun `close wipes records and rejects further access - close is idempotent`() {
        val state = VaultState.empty()
        state.signalRecords["identity_keypair"] = byteArrayOf(1, 2, 3, 4, 5)
        val recordRef = state.signalRecords.getValue("identity_keypair")
        val runtime = runtimeOf(state)

        runtime.close()

        // The record's backing array was zeroed by state.wipe().
        assertTrue("record bytes wiped on close", recordRef.all { it == 0.toByte() })
        // After close, every access throws.
        assertThrows(IllegalStateException::class.java) { runtime.read { it.rosterJson } }
        assertThrows(IllegalStateException::class.java) { runtime.mutate { it.rosterJson = "y" } }
        assertThrows(IllegalStateException::class.java) { runtime.flushBeforeAck() }
        // Idempotent: a second close is a silent no-op.
        runtime.close()
    }

    @Test
    fun `an over-capacity mutate throws VaultCapacityException, latches the flag, retains in memory, does not persist`() {
        val persisted = AtomicInteger(0)
        val runtime = runtimeOf(persist = { _, _ -> persisted.incrementAndGet() })

        // A normal small write persists and leaves the flag clear.
        runtime.mutate { it.signalRecords["small"] = byteArrayOf(1, 2, 3) }
        runtime.flushBeforeAck()
        assertEquals(1, persisted.get())
        assertFalse("no capacity failure yet", runtime.capacityExceeded)

        // Incompressible bytes just over the region cap → encode throws before session.update.
        val huge = ops.randomBytes(VaultStateCodec.MAX_PAYLOAD_CONTENT_BYTES + 5_000)
        assertThrows(VaultCapacityException::class.java) {
            runtime.mutate { it.signalRecords["huge"] = huge }
        }

        // Latched, retained in memory, but NOT persisted (the session never saw the oversized payload).
        assertTrue("capacity flag latched", runtime.capacityExceeded)
        assertTrue("the mutation is retained in memory", runtime.read { it.signalRecords.containsKey("huge") })
        runtime.flushBeforeAck() // session is clean (still holds the last small payload)
        assertEquals("the oversized state was never persisted", 1, persisted.get())

        // Recovery: removing the huge record encodes fine and persists again; the flag stays latched.
        runtime.mutate { it.signalRecords.remove("huge") }
        runtime.flushBeforeAck()
        assertEquals("a small state persists after recovery", 2, persisted.get())
        assertTrue("capacityExceeded stays latched (never reset here)", runtime.capacityExceeded)
    }

    @Test
    fun `concurrent mutates from two threads serialize with no lost updates`() {
        val runtime = runtimeOf()
        val perThread = 500

        fun increment() = runtime.mutate { state ->
            val current = state.signalRecords["counter"]?.let { bytesToInt(it) } ?: 0
            state.signalRecords["counter"] = intToBytes(current + 1)
        }

        val a = Thread { repeat(perThread) { increment() } }
        val b = Thread { repeat(perThread) { increment() } }
        a.start(); b.start(); a.join(); b.join()

        val total = runtime.read { bytesToInt(it.signalRecords.getValue("counter")) }
        assertEquals("no lost updates — mutate serialized on the single lock", perThread * 2, total)
    }

    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun bytesToInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
}
