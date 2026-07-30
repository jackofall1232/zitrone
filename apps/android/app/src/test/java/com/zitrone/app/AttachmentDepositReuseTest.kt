// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app

import com.zitrone.app.crypto.AttachmentCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One blob per message across retries (0.10.2 item 5a) — the crypto half, tested where it can be
 * tested without constructing `MessagingCoordinator`.
 *
 * The defect: `encrypt` drew a fresh token per call and `blobId = sha256(token)`, so every 0.10.1
 * retry deposited a NEW blob and left the previous one to its full TTL. Blobs are the dimension that
 * threatens the box — one is up to 8,454,180 B, roughly 545 accounts' worth of disk.
 */
class AttachmentDepositReuseTest {

    private val plain = ByteArray(4096) { (it % 251).toByte() }

    @Test
    fun `a fresh encrypt draws new secrets every time, so two messages never collide`() {
        val a = AttachmentCrypto.encrypt(plain)
        val b = AttachmentCrypto.encrypt(plain)

        assertFalse("two messages must not share a blob id", a.blobId.contentEquals(b.blobId))
        assertFalse("two messages must not share a token", a.token.contentEquals(b.token))
        assertFalse("two messages must not share a key", a.key.contentEquals(b.key))
    }

    @Test
    fun `reusing the token and key keeps blobId stable, so a retry cannot orphan the first blob`() {
        val first = AttachmentCrypto.encrypt(plain)

        val retry = AttachmentCrypto.encrypt(plain, first.token, first.key)

        assertArrayEquals("the retry must deposit to the SAME row", first.blobId, retry.blobId)
        assertArrayEquals(first.token, retry.token)
        assertArrayEquals(first.key, retry.key)
    }

    @Test
    fun `the box still differs per attempt, and that is deliberately safe`() {
        // Byte-identity is NOT the goal and forcing it would be the dangerous option: a derived nonce
        // over `MessagePadding`'s random fill means a repeated (key, nonce) pair over DIFFERING
        // plaintext, the one GCM failure that is catastrophic. A fresh nonce under a reused key is the
        // ordinary safe construction, and it is sufficient because `ON CONFLICT DO NOTHING` keeps
        // whichever attempt landed first while the nonce travels INSIDE the box.
        val first = AttachmentCrypto.encrypt(plain)
        val retry = AttachmentCrypto.encrypt(plain, first.token, first.key)

        assertFalse(
            "a derived/reused nonce would be key+nonce reuse over differing padded plaintext",
            first.box.contentEquals(retry.box),
        )
    }

    @Test
    fun `either attempt's stored bytes open under the shared key, which is why the box need not match`() {
        // The property that lets us hold 96 bytes instead of 8 MiB: the relay keeps ONE of the two
        // boxes and we cannot control which, so both must be openable with the memoized key.
        val first = AttachmentCrypto.encrypt(plain)
        val retry = AttachmentCrypto.encrypt(plain, first.token, first.key)

        for ((label, box) in listOf("first" to first.box, "retry" to retry.box)) {
            val opened = AttachmentCrypto.decrypt(retry.key, box, retry.sha256, plain.size)
            assertArrayEquals("$label attempt's stored bytes must open under the shared key", plain, opened)
        }
    }

    @Test
    fun `half-reuse is refused, because both of its outcomes are defects`() {
        // token-only → stable blobId with a NEW key: the relay keeps attempt 1's bytes while the
        // envelope carries attempt 2's key, so the recipient sees corruption rather than a failure.
        // key-only → a NEW blobId with the old key: a fresh orphan, which is the thing being fixed.
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentCrypto.encrypt(plain, ByteArray(32), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentCrypto.encrypt(plain, null, ByteArray(32))
        }
    }

    @Test
    fun `the token is never derivable from anything the relay sees`() {
        // blobId = sha256(token) and the token IS the redemption capability, while the message id is
        // cleartext to the relay for routing. So the token must stay a random draw that callers
        // MEMOIZE — never a derivation. Two encrypts of identical input under no reuse must differ,
        // which is the observable form of "not derived from the input".
        val a = AttachmentCrypto.encrypt(plain)
        val b = AttachmentCrypto.encrypt(plain)

        assertEquals(32, a.token.size)
        assertTrue(
            "a token derived from message content or id would repeat for identical input",
            !a.token.contentEquals(b.token),
        )
    }
}

/**
 * The WIRING half — pinned against source, because it cannot be pinned against behaviour.
 *
 * The round-2 mutation sweep for this item proved the gap: deleting the coordinator's reuse (going
 * back to `AttachmentCrypto.encrypt(bytes)`) broke **nothing**, because every behavioural test above
 * exercises `AttachmentCrypto` directly and nothing in the suite can construct a
 * `MessagingCoordinator` (it needs `Context`, `NotificationScheduler`, `SignalProtocolManager`…).
 *
 * So this is the same split 0.10.1 landed on: behaviour where behaviour is reachable, source for the
 * wiring. Declared rather than implied — the honest description is that the crypto is tested and the
 * wiring is asserted.
 */
class AttachmentDepositWiringTest {

    private fun coordinator(): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "src/main/java/com/zitrone/app/MessagingCoordinator.kt")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("MessagingCoordinator.kt not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the attachment encrypt reuses the memoized deposit secrets`() {
        assertTrue(
            "a retry draws fresh secrets again, so every retry orphans the previous blob — up to " +
                "8 MiB each, held for the full TTL",
            "AttachmentCrypto.encrypt(bytes, memo?.token, memo?.key)" in coordinator(),
        )
    }

    @Test
    fun `the deposit is memoized on the first attempt`() {
        assertTrue(
            "nothing is stored, so a retry has no secrets to reuse and the reuse above is dead code",
            "attachmentDeposits[messageId] = AttachmentDeposit(blob.token, blob.key)" in coordinator(),
        )
    }

    @Test
    fun `the memo is released on every terminal outcome, so it cannot become a heap leak`() {
        // The trade this fix must NOT make: disk orphans for unbounded heap.
        //
        // **The three sites changed in 0.10.3 and this count did not notice — corrected here.** The
        // pin was written for: relay took it / recipient got it / local copy discarded. 0.10.3
        // replaced the third with `settleAttachment(messageId)` and added a release inside
        // settleAttachment's RELEASE_ONLY branch. Net count stayed 3, so this test stayed green
        // while its stated invariant became false — a source-pinning tripwire surviving a refactor
        // that changed its meaning, which is the exact failure it exists to prevent.
        //
        // So pin the SITES, not just the total. A bare count cannot tell "moved" from "lost".
        // Comments are STRIPPED before matching. Round-2 review found the obvious hole in the first
        // version: `// settleAttachment(messageId)` still satisfies a substring check, so commenting
        // out the reclamation path left every assertion green. A source-text pin that cannot tell
        // code from a comment pins nothing.
        val code = stripComments(coordinator())
        assertEquals(
            "a release point was lost or added; the deposit map then grows for the process's lifetime",
            3,
            Regex("releaseDeposit\\s*\\(\\s*messageId\\s*\\)").findAll(code).count(),
        )
        // The terminal outcome that no longer releases directly — it routes through the settle
        // decision, which may RELEASE_ONLY or ABANDON. If this reverts to a bare releaseDeposit,
        // the blob stops being reclaimable and 0.10.3's whole point is lost.
        assertTrue(
            "the contact-deleted-mid-send path must route through settleAttachment, not release directly",
            Regex("settleAttachment\\s*\\(\\s*messageId\\s*\\)").containsMatchIn(code),
        )
        // The release that settleAttachment owns. Losing it turns every handed-off attachment into
        // a permanent memo entry — the heap-leak side of the trade.
        assertTrue(
            "settleAttachment's RELEASE_ONLY branch must still release the memo",
            Regex(
                "RELEASE_ONLY\\s*\\)\\s*\\{\\s*releaseDeposit\\s*\\(\\s*messageId\\s*\\)",
            ).containsMatchIn(code),
        )
    }

    /**
     * Remove `//` line comments and block comments so the pins above match CODE, not prose.
     *
     * Crude on purpose: it will also chew a `//` inside a string literal (a URL, say). That is
     * harmless here — these assertions look only for specific call patterns, and mangling an
     * unrelated string cannot manufacture one. Being wrong in the direction of matching LESS is the
     * safe direction for a tripwire.
     */
    private fun stripComments(code: String): String = code
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")
}
