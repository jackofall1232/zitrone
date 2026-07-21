// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Per-contact cryptographic teardown for the TypeScript ratchet (web/desktop).
 *
 * Conceptually mirrors Android's destroyContactCrypto: destroy Double Ratchet
 * session material + the pinned/verified identity for ONE peer, without
 * touching other peers. Tombstones are TTL-bounded so straggler envelopes
 * from a just-deleted contact are dropped instead of resurrecting the roster,
 * while a genuinely later re-add past the window is not silently blocked.
 *
 * Not a port of the Kotlin/libsignal code path — this operates on the
 * libsodium/Ed25519 KeyStore + RatchetSession model used by packages/crypto.
 */

import type { RatchetSession } from "./ratchet.js";
import type { KeyStore } from "./keystore.js";
import { wipe } from "./vault.js";

/**
 * How long a deleted contact stays tombstoned. Matches Android's window:
 * covers the relay's undelivered-envelope window (72 h) plus margin for a
 * message queued just before deletion and clock skew.
 */
export const CONTACT_TOMBSTONE_WINDOW_MS = 96 * 60 * 60 * 1000;

/** Overwrite every secret field of a live Double Ratchet session in place. */
export function wipeRatchetSession(session: RatchetSession): void {
  wipe(session.rootKey);
  wipe(session.dhSelf.privateKey);
  wipe(session.dhSelf.publicKey);
  if (session.dhRemote) wipe(session.dhRemote);
  if (session.sendingChainKey) wipe(session.sendingChainKey);
  if (session.receivingChainKey) wipe(session.receivingChainKey);
  for (const key of session.skippedKeys.values()) wipe(key);
  session.skippedKeys.clear();
  session.dhRemote = null;
  session.sendingChainKey = null;
  session.receivingChainKey = null;
  session.sendCounter = 0;
  session.receiveCounter = 0;
  session.previousSendCounter = 0;
}

/**
 * Destroy one contact's crypto in the keystore: remove their session blob and
 * verified-identity pin, optionally zeroing a live deserialized session first.
 *
 * Returns `false` only when `contactId` is empty (nothing to target). In-memory
 * mutation always succeeds; durable fail-abort is the caller's responsibility
 * (persist the keystore and abort the UI deletion if the write fails), matching
 * Android's "don't report deleted if the wipe did not reach disk" rule.
 *
 * Scoped: other peers' sessions and verified pins are untouched.
 */
export function destroyContactCrypto(
  keyStore: KeyStore,
  contactId: string,
  liveSession?: RatchetSession | null,
): boolean {
  if (!contactId) return false;
  if (liveSession) wipeRatchetSession(liveSession);
  delete keyStore.sessions[contactId];
  delete keyStore.verifiedContacts[contactId];
  return true;
}

/**
 * Record that `contactId` was just deleted so inbound stragglers within
 * {@link CONTACT_TOMBSTONE_WINDOW_MS} can be dropped. Prunes expired entries.
 */
export function recordContactDeletion(
  keyStore: KeyStore,
  contactId: string,
  nowMs: number = Date.now(),
): void {
  if (!contactId) return;
  const map: Record<string, number> = { ...(keyStore.deletedContacts ?? {}) };
  for (const [id, at] of Object.entries(map)) {
    if (nowMs - at >= CONTACT_TOMBSTONE_WINDOW_MS) delete map[id];
  }
  map[contactId] = nowMs;
  keyStore.deletedContacts = map;
}

/**
 * Whether `contactId` was deleted within the straggler window and so an
 * inbound message from it must be dropped before decrypt (avoids TOFU
 * re-establishment and roster resurrection).
 */
export function wasContactRecentlyDeleted(
  keyStore: KeyStore,
  contactId: string,
  nowMs: number = Date.now(),
): boolean {
  const at = keyStore.deletedContacts?.[contactId];
  if (at == null) return false;
  if (nowMs - at >= CONTACT_TOMBSTONE_WINDOW_MS) {
    // Lazy prune so a stale entry does not block a later legitimate re-add.
    const map = { ...(keyStore.deletedContacts ?? {}) };
    delete map[contactId];
    keyStore.deletedContacts = map;
    return false;
  }
  return true;
}

/** Drop all deletion tombstones (account wipe / device reset). */
export function clearContactDeletions(keyStore: KeyStore): void {
  if (keyStore.deletedContacts) keyStore.deletedContacts = {};
}
