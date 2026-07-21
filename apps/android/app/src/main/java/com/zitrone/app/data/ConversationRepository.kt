// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversation index for the contact roster.
 *
 * Unlike [MessageRepository] — which stays IN-MEMORY on purpose, so decrypted
 * message plaintext never touches disk — the ROSTER is persisted (encrypted,
 * via [RosterStore]). It has to be: it used to live only in an in-memory
 * StateFlow, so any full process restart (an app update forces one) wiped it,
 * leaving a logged-in user with an empty roster and — worse — losing each
 * contact's pinned anti-key-substitution key and verified flag. See [RosterStore]
 * for the full write-up. Conversation previews NEVER carry message content; the
 * UI always renders the literal string "Encrypted message".
 *
 * Persistence invariants:
 *  - Every mutation flows through the single [setConversations] choke point,
 *    which writes the whole roster back to disk.
 *  - A read/parse failure NEVER becomes a silent wipe: on any load error we
 *    start empty IN MEMORY ONLY and flip [readOnly] so [persist] refuses to
 *    overwrite the possibly-recoverable stored blob (a transient glitch, or a
 *    blob written by a newer client, must not clobber good data).
 */
class ConversationRepository(
    private val store: RosterStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    /**
     * When true, [persist] is a no-op: the stored blob was unreadable or came
     * from a newer schema, so we must not overwrite it. The never-silent-wipe
     * guard.
     */
    private var readOnly = false

    /**
     * Persisted deleted-contact tombstones: contactId → deletedAtEpochMs. A
     * message arriving for a contact deleted within [TOMBSTONE_WINDOW_MS] is
     * dropped rather than resurrecting the contact (see [wasRecentlyDeleted]).
     * Loaded at boot and pruned to the window so it can't grow unbounded and so a
     * genuinely-later fresh contact attempt (past the relay's straggler window)
     * is NOT silently blocked. Guarded by the instance monitor like every other
     * mutator.
     */
    private val tombstones = mutableMapOf<String, Long>()

    init {
        // Load + prune deleted-contact tombstones (independent of the roster
        // blob, so a tombstone survives a restart even if the roster is
        // read-only). A parse error just starts with none.
        runCatching { store.readTombstonesBlob() }.getOrNull()?.let { raw ->
            runCatching {
                val obj = JSONObject(raw)
                val now = clock()
                obj.keys().forEach { id ->
                    val at = obj.getLong(id)
                    if (now - at < TOMBSTONE_WINDOW_MS) tombstones[id] = at
                }
            }
        }

        // Load-and-seed at boot instead of starting empty. Guarded so no read or
        // parse error can wipe the roster or crash boot.
        val raw = runCatching { store.readBlob() }.getOrNull()
        val loaded: List<Conversation>? = if (raw.isNullOrBlank()) {
            // No roster has ever been persisted: fresh install OR an install
            // wiped by the pre-fix in-memory-only bug. `null` signals the repair
            // path below.
            null
        } else {
            runCatching { deserialize(raw) }.getOrElse {
                // Malformed/unreadable blob. Do NOT treat as a wipe: start empty
                // in memory and go read-only so we never clobber the stored blob
                // (a fixed build might still recover it).
                readOnly = true
                emptyList()
            }
        }

        if (loaded == null) {
            // One-time repair: reconstruct bare conversations from orphaned
            // Signal-store records, then persist so it never has to run again.
            val repaired = runCatching { repairFromSignalStore() }.getOrDefault(emptyList())
            _conversations.value = repaired
            if (repaired.isNotEmpty()) persist()
        } else {
            _conversations.value = loaded
        }
    }

    fun find(conversationId: String): Conversation? =
        _conversations.value.firstOrNull { it.id == conversationId }

    fun findByContact(contactId: String): Conversation? =
        _conversations.value.firstOrNull { it.contactId == contactId }

    @Synchronized
    fun upsert(conversation: Conversation) {
        setConversations(
            _conversations.value
                .filterNot { it.id == conversation.id }
                .plus(conversation)
                .sortedByDescending { it.lastActivityMs },
        )
    }

    /** Bumps activity + unread counter when a message arrives. */
    @Synchronized
    fun onIncomingMessage(contactId: String, displayName: String? = null): Conversation {
        val existing = findByContact(contactId)
        val updated = existing?.copy(
            unreadCount = existing.unreadCount + 1,
            lastActivityMs = clock(),
        ) ?: Conversation(
            id = contactId,
            contactId = contactId,
            displayName = displayName ?: "Unknown contact",
            unreadCount = 1,
            lastActivityMs = clock(),
        )
        upsert(updated)
        return updated
    }

    @Synchronized
    fun onOutgoingMessage(conversationId: String) {
        find(conversationId)?.let { upsert(it.copy(lastActivityMs = clock())) }
    }

    @Synchronized
    fun markConversationRead(conversationId: String) {
        find(conversationId)?.let { upsert(it.copy(unreadCount = 0)) }
    }

    @Synchronized
    fun setVerified(conversationId: String, verified: Boolean) {
        find(conversationId)?.let {
            upsert(it.copy(verified = verified, keyChanged = if (verified) false else it.keyChanged))
        }
    }

    /**
     * Updates the local-only display label for a contact. Does not touch session
     * state, identity keys, pinned keys, safety numbers, or anything transmitted
     * off-device — [Conversation.displayName] is UI chrome stored in the roster blob.
     *
     * @return the sanitized name on success, or null if validation rejected the input
     *         (empty after trim, or over [DISPLAY_NAME_MAX_LEN]).
     */
    @Synchronized
    fun setDisplayName(conversationId: String, rawName: String): String? {
        val name = sanitizeDisplayName(rawName) ?: return null
        find(conversationId)?.let { upsert(it.copy(displayName = name)) }
            ?: return null
        return name
    }

    /** Identity key changed for a contact — surface the warning badge. */
    @Synchronized
    fun flagKeyChanged(contactId: String, newIdentityKeyBase64: String) {
        findByContact(contactId)?.let {
            upsert(
                it.copy(
                    keyChanged = true,
                    verified = false,
                    contactIdentityKeyBase64 = newIdentityKeyBase64,
                ),
            )
        }
    }

    /**
     * The relay returned an identity key that doesn't match the out-of-band key
     * pinned when this contact was added. Raise the warning and drop verified,
     * but KEEP the pinned key (do not adopt the relay's substitute).
     */
    @Synchronized
    fun flagIdentityMismatch(contactId: String) {
        findByContact(contactId)?.let {
            upsert(it.copy(keyChanged = true, verified = false))
        }
    }

    @Synchronized
    fun remove(conversationId: String) {
        setConversations(_conversations.value.filterNot { it.id == conversationId })
    }

    /**
     * Like [remove], but flushes the roster to disk **synchronously** and reports
     * whether the write reached disk. Used by contact deletion so the removal is
     * durable — a crash right after the crypto teardown must not leave a stale
     * roster blob that resurrects the deleted contact. Returns true when the
     * store is [readOnly] (nothing is persisted, so there is nothing to lose).
     */
    @Synchronized
    fun removeDurably(conversationId: String): Boolean {
        _conversations.value = _conversations.value.filterNot { it.id == conversationId }
        if (readOnly) return true
        return runCatching { store.writeBlobDurably(serialize(_conversations.value)) }
            .getOrDefault(false)
    }

    /**
     * Records that [contactId] was just deleted, so a straggler message from it
     * (within [TOMBSTONE_WINDOW_MS]) is dropped rather than resurrecting the
     * contact — including after a process restart, since the tombstones are
     * persisted. Prunes expired entries on the way through. Call from the
     * contact-deletion path.
     */
    @Synchronized
    fun recordDeletion(contactId: String) {
        val now = clock()
        tombstones.entries.removeAll { now - it.value >= TOMBSTONE_WINDOW_MS }
        tombstones[contactId] = now
        runCatching { store.writeTombstonesBlob(serializeTombstones()) }
    }

    /**
     * Whether [contactId] was deleted within the straggler window and so an
     * inbound message from it must be dropped. False for a never-deleted sender
     * (a first-time inbound sender still becomes an "Unknown contact") and, past
     * the window, for a genuinely-later fresh contact attempt.
     */
    @Synchronized
    fun wasRecentlyDeleted(contactId: String): Boolean {
        val at = tombstones[contactId] ?: return false
        return clock() - at < TOMBSTONE_WINDOW_MS
    }

    private fun serializeTombstones(): String =
        JSONObject().apply { tombstones.forEach { (id, at) -> put(id, at) } }.toString()

    @Synchronized
    fun clearAll() {
        // Account wipe: drop the deleted-contact tombstones too, so no trace of
        // who was deleted survives on disk past the wipe.
        if (tombstones.isNotEmpty()) {
            tombstones.clear()
            runCatching { store.writeTombstonesBlob(serializeTombstones()) }
        }
        setConversations(emptyList())
    }

    // -- persistence ----------------------------------------------------------

    /**
     * The single write choke point: update the in-memory state AND persist it.
     * Routing every mutation through here is what guarantees no mutation can
     * forget to write the roster back to disk.
     */
    private fun setConversations(next: List<Conversation>) {
        _conversations.value = next
        persist()
    }

    /**
     * Persist the whole roster. A no-op when [readOnly] (never overwrite a blob
     * we couldn't safely read) and guarded so a write failure can't crash a
     * mutation on the UI path.
     */
    private fun persist() {
        if (readOnly) return
        runCatching { store.writeBlob(serialize(_conversations.value)) }
    }

    private fun repairFromSignalStore(): List<Conversation> =
        store.orphanedContacts().map { orphan ->
            Conversation(
                id = orphan.contactId,
                contactId = orphan.contactId,
                // Display names only ever lived in memory — unrecoverable.
                displayName = "Unnamed contact",
                contactIdentityKeyBase64 = orphan.identityKeyBase64,
                // No out-of-band pinned key survives the wipe; TOFU from here.
                pinnedIdentityKeyBase64 = null,
                verified = false,
            )
        }

    /**
     * Parse the stored blob. An unknown-NEWER [SCHEMA_VERSION] is treated as
     * read-only (start empty in memory, don't overwrite) so a downgrade can't
     * clobber a roster written by a newer client.
     */
    private fun deserialize(raw: String): List<Conversation> {
        val root = JSONObject(raw)
        if (root.getInt(KEY_SCHEMA) > SCHEMA_VERSION) {
            readOnly = true
            return emptyList()
        }
        val arr = root.getJSONArray(KEY_CONVERSATIONS)
        return (0 until arr.length()).map { conversationFromJson(arr.getJSONObject(it)) }
    }

    private fun serialize(conversations: List<Conversation>): String {
        val arr = JSONArray()
        conversations.forEach { arr.put(conversationToJson(it)) }
        return JSONObject().apply {
            put(KEY_SCHEMA, SCHEMA_VERSION)
            put(KEY_CONVERSATIONS, arr)
        }.toString()
    }

    private fun conversationToJson(c: Conversation): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("contact_id", c.contactId)
        put("display_name", c.displayName)
        put("contact_identity_key", c.contactIdentityKeyBase64 ?: JSONObject.NULL)
        put("pinned_identity_key", c.pinnedIdentityKeyBase64 ?: JSONObject.NULL)
        put("verified", c.verified)
        put("key_changed", c.keyChanged)
        put("unread_count", c.unreadCount)
        put("last_activity_ms", c.lastActivityMs)
    }

    private fun conversationFromJson(o: JSONObject): Conversation = Conversation(
        id = o.getString("id"),
        contactId = o.getString("contact_id"),
        displayName = o.getString("display_name"),
        contactIdentityKeyBase64 =
            if (o.isNull("contact_identity_key")) null else o.getString("contact_identity_key"),
        pinnedIdentityKeyBase64 =
            if (o.isNull("pinned_identity_key")) null else o.getString("pinned_identity_key"),
        verified = o.getBoolean("verified"),
        keyChanged = o.getBoolean("key_changed"),
        // Best-effort — acceptable if these reset; default when an older blob omits them.
        unreadCount = o.optInt("unread_count", 0),
        lastActivityMs = o.optLong("last_activity_ms", 0L),
    )

    companion object {
        /**
         * Stored-JSON schema version. Bump on an incompatible field change; an
         * unknown-newer value makes this build treat the blob as read-only.
         */
        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA = "schema_version"
        private const val KEY_CONVERSATIONS = "conversations"

        /**
         * How long a deleted contact stays tombstoned. Covers the relay's
         * undelivered-envelope window (72 h) plus margin for a message queued
         * just before deletion and clock skew, so every possible straggler is
         * dropped — while a genuinely-later fresh contact attempt past this
         * window is NOT silently blocked.
         */
        const val TOMBSTONE_WINDOW_MS = 96L * 60 * 60 * 1000

        /** Hard cap on contact labels rendered in the UI (and stored in the roster). */
        const val DISPLAY_NAME_MAX_LEN = 64

        /**
         * Trim, strip control characters, collapse whitespace, reject empty /
         * over-long. Pure function for unit tests. Display names are local labels
         * only — never fed into crypto or the wire.
         */
        fun sanitizeDisplayName(raw: String): String? {
            val collapsed = buildString(raw.length) {
                var pendingSpace = false
                for (ch in raw.trim()) {
                    when {
                        ch.isISOControl() -> Unit
                        ch.isWhitespace() -> pendingSpace = true
                        else -> {
                            if (pendingSpace && isNotEmpty()) append(' ')
                            pendingSpace = false
                            append(ch)
                        }
                    }
                }
            }
            if (collapsed.isEmpty() || collapsed.length > DISPLAY_NAME_MAX_LEN) return null
            return collapsed
        }
    }
}
