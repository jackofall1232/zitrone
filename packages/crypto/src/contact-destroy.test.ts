// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import {
  generateIdentityKeyPair,
  generateOneTimePrekeys,
  generateSignedPrekey,
} from "./keys.js";
import { x3dhInitiate, x3dhRespond } from "./x3dh.js";
import { ratchetEncrypt, ratchetDecrypt, type RatchetSession } from "./ratchet.js";
import { utf8Encode, utf8Decode } from "./encoding.js";
import type { KeyStore } from "./keystore.js";
import {
  CONTACT_TOMBSTONE_WINDOW_MS,
  clearContactDeletions,
  destroyContactCrypto,
  recordContactDeletion,
  wasContactRecentlyDeleted,
  wipeRatchetSession,
} from "./contact-destroy.js";

function emptyKeyStore(): KeyStore {
  return {
    version: 1,
    accountId: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    identityKey: { publicKey: "aa", privateKey: "bb" },
    signedPrekeys: [],
    oneTimePrekeys: [],
    sessions: {},
    verifiedContacts: {},
  };
}

async function establishedSessions(): Promise<{
  aliceSession: RatchetSession;
  bobSession: RatchetSession;
}> {
  const alice = await generateIdentityKeyPair();
  const bob = await generateIdentityKeyPair();
  const bobSigned = await generateSignedPrekey(bob, 1);
  const [bobOneTime] = await generateOneTimePrekeys(1);

  const init = await x3dhInitiate(alice, {
    identityKey: bob.publicKey,
    signedPrekey: {
      id: bobSigned.id,
      publicKey: bobSigned.publicKey,
      signature: bobSigned.signature,
    },
    oneTimePrekey: { id: bobOneTime!.id, publicKey: bobOneTime!.publicKey },
  });
  const bobSession = await x3dhRespond(
    bob,
    bobSigned,
    bobOneTime!,
    alice.publicKey,
    init.ephemeralPublicKey,
  );
  return { aliceSession: init.session, bobSession };
}

describe("wipeRatchetSession", () => {
  it("zeros secret material so further encrypt fails closed", async () => {
    const { aliceSession } = await establishedSessions();
    const rootBefore = aliceSession.rootKey.slice();
    expect(rootBefore.some((b) => b !== 0)).toBe(true);

    wipeRatchetSession(aliceSession);

    expect(aliceSession.rootKey.every((b) => b === 0)).toBe(true);
    expect(aliceSession.dhSelf.privateKey.every((b) => b === 0)).toBe(true);
    expect(aliceSession.sendingChainKey).toBeNull();
    expect(aliceSession.skippedKeys.size).toBe(0);

    await expect(ratchetEncrypt(aliceSession, utf8Encode("should fail"))).rejects.toThrow();
  });

  it("does not leave skipped keys usable after wipe", async () => {
    const { aliceSession, bobSession } = await establishedSessions();
    // Create skipped keys on bob by delivering out of order.
    const m1 = await ratchetEncrypt(aliceSession, utf8Encode("first"));
    const m2 = await ratchetEncrypt(aliceSession, utf8Encode("second"));
    await ratchetDecrypt(bobSession, m2);
    expect(bobSession.skippedKeys.size).toBeGreaterThan(0);
    wipeRatchetSession(bobSession);
    expect(bobSession.skippedKeys.size).toBe(0);
    await expect(ratchetDecrypt(bobSession, m1)).rejects.toThrow();
  });
});

describe("destroyContactCrypto", () => {
  it("removes only the targeted peer session and verified pin", async () => {
    const { aliceSession } = await establishedSessions();
    const store = emptyKeyStore();
    store.sessions["bob"] = { session: "blob-bob" };
    store.sessions["carol"] = { session: "blob-carol" };
    store.verifiedContacts["bob"] = "bob-id-key";
    store.verifiedContacts["carol"] = "carol-id-key";

    expect(destroyContactCrypto(store, "bob", aliceSession)).toBe(true);

    expect(store.sessions["bob"]).toBeUndefined();
    expect(store.verifiedContacts["bob"]).toBeUndefined();
    expect(store.sessions["carol"]).toEqual({ session: "blob-carol" });
    expect(store.verifiedContacts["carol"]).toBe("carol-id-key");
    // Live session wiped as a side effect.
    expect(aliceSession.rootKey.every((b) => b === 0)).toBe(true);
  });

  it("returns false for an empty contact id and leaves the store alone", () => {
    const store = emptyKeyStore();
    store.sessions["bob"] = {};
    expect(destroyContactCrypto(store, "")).toBe(false);
    expect(store.sessions["bob"]).toEqual({});
  });

  it("succeeds when the contact had no prior session (session-less lemon-drop peer)", () => {
    const store = emptyKeyStore();
    store.sessions["mobile-peer"] = { session: null, identityKey: "x" };
    expect(destroyContactCrypto(store, "mobile-peer", null)).toBe(true);
    expect(store.sessions["mobile-peer"]).toBeUndefined();
  });
});

describe("deletion tombstones", () => {
  it("records, detects, and expires within the straggler window", () => {
    const store = emptyKeyStore();
    const t0 = 1_700_000_000_000;

    expect(wasContactRecentlyDeleted(store, "bob", t0)).toBe(false);

    recordContactDeletion(store, "bob", t0);
    expect(wasContactRecentlyDeleted(store, "bob", t0 + 1000)).toBe(true);
    expect(wasContactRecentlyDeleted(store, "bob", t0 + CONTACT_TOMBSTONE_WINDOW_MS - 1)).toBe(
      true,
    );
    // Past the window: not blocked, and the stale entry is pruned.
    expect(wasContactRecentlyDeleted(store, "bob", t0 + CONTACT_TOMBSTONE_WINDOW_MS)).toBe(false);
    expect(store.deletedContacts?.["bob"]).toBeUndefined();
  });

  it("is scoped — deleting bob does not tombstone carol", () => {
    const store = emptyKeyStore();
    const t0 = 1_700_000_000_000;
    recordContactDeletion(store, "bob", t0);
    expect(wasContactRecentlyDeleted(store, "carol", t0)).toBe(false);
  });

  it("prunes expired tombstones when recording a new deletion", () => {
    const store = emptyKeyStore();
    const t0 = 1_700_000_000_000;
    recordContactDeletion(store, "old", t0);
    recordContactDeletion(store, "new", t0 + CONTACT_TOMBSTONE_WINDOW_MS + 1);
    expect(store.deletedContacts?.["old"]).toBeUndefined();
    expect(store.deletedContacts?.["new"]).toBe(t0 + CONTACT_TOMBSTONE_WINDOW_MS + 1);
  });

  it("clearContactDeletions wipes the map (account wipe)", () => {
    const store = emptyKeyStore();
    recordContactDeletion(store, "bob", Date.now());
    clearContactDeletions(store);
    expect(store.deletedContacts).toEqual({});
  });
});
