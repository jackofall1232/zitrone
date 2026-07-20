// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import { createLemonDrop, openLemonDrop } from "./lemondrop.js";
import {
  generateIdentityKeyPair,
  generateOneTimePrekeys,
  generateSignedPrekey,
  type IdentityKeyPair,
  type OneTimePrekey,
  type SignedPrekey,
} from "./keys.js";
import { type DecodedPreKeyBundle } from "./x3dh.js";

// A recipient's published material plus everything needed to open a drop
// addressed to them. `signedPrekeys` may hold more than one when we exercise
// rotation; the bundle publishes just one.
interface Recipient {
  accountId: string;
  identity: IdentityKeyPair;
  signedPrekeys: SignedPrekey[];
  oneTimePrekeys: OneTimePrekey[];
  bundle: DecodedPreKeyBundle;
}

async function makeRecipient(options: { withOtp?: boolean } = {}): Promise<Recipient> {
  const withOtp = options.withOtp ?? true;
  const identity = await generateIdentityKeyPair();
  const signed = await generateSignedPrekey(identity, 1);
  const otps = withOtp ? await generateOneTimePrekeys(1) : [];
  const bundle: DecodedPreKeyBundle = {
    identityKey: identity.publicKey,
    signedPrekey: { id: signed.id, publicKey: signed.publicKey, signature: signed.signature },
    oneTimePrekey: otps[0] ? { id: otps[0].id, publicKey: otps[0].publicKey } : null,
  };
  return {
    accountId: crypto.randomUUID(),
    identity,
    signedPrekeys: [signed],
    oneTimePrekeys: otps,
    bundle,
  };
}

async function makeSender(): Promise<{ accountId: string; identity: IdentityKeyPair }> {
  return { accountId: crypto.randomUUID(), identity: await generateIdentityKeyPair() };
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes.slice().buffer));
}

// Every test here runs a REAL difficulty-20 hashcash solve (~1M SHA-256 on
// average, with a long unlucky tail) inside createLemonDrop — honest work that
// a shared CI runner can stretch well past vitest's 5 s default. The budget
// covers the tail instead of gambling on runner load.
describe("lemon drops", { timeout: 30_000 }, () => {
  it("round-trips a message to the intended recipient", async () => {
    const sender = await makeSender();
    const recipient = await makeRecipient();

    const drop = await createLemonDrop({
      senderAccountId: sender.accountId,
      senderIdentity: sender.identity,
      recipientAccountId: recipient.accountId,
      recipientBundle: recipient.bundle,
      text: "meet at the lemon tree, midnight",
    });

    expect(drop.url).toBe(`https://zitrone.app/d/${drop.url.split("/d/")[1]}`);
    expect(drop.qrId).toHaveLength(16);

    const result = await openLemonDrop({
      myIdentity: recipient.identity,
      mySignedPrekeys: recipient.signedPrekeys,
      myOneTimePrekeys: recipient.oneTimePrekeys,
      ciphertext: drop.ciphertext,
    });

    expect(result.outcome).toBe("message");
    if (result.outcome !== "message") return;
    expect(result.text).toBe("meet at the lemon tree, midnight");
    expect(result.senderAccountId).toBe(sender.accountId);
    expect(result.senderIdentityKey).toEqual(sender.identity.publicKey);
    expect(result.burnToken).toHaveLength(32);
    expect(result.usedOneTimePrekeyId).toBe(recipient.oneTimePrekeys[0]!.id);
    // Only a successful decryptor recovers the token whose hash was deposited.
    expect(await sha256(result.burnToken)).toEqual(drop.burnHash);
  });

  it("tells a wrong recipient it is not for them", async () => {
    const sender = await makeSender();
    const recipient = await makeRecipient();
    const stranger = await makeRecipient();

    const drop = await createLemonDrop({
      senderAccountId: sender.accountId,
      senderIdentity: sender.identity,
      recipientAccountId: recipient.accountId,
      recipientBundle: recipient.bundle,
      text: "not for you",
    });

    const result = await openLemonDrop({
      myIdentity: stranger.identity,
      mySignedPrekeys: stranger.signedPrekeys,
      myOneTimePrekeys: stranger.oneTimePrekeys,
      ciphertext: drop.ciphertext,
    });
    expect(result.outcome).toBe("not-recipient");
  });

  it("treats a tampered blob as not-for-you (the seal MAC fails)", async () => {
    const sender = await makeSender();
    const recipient = await makeRecipient();

    const drop = await createLemonDrop({
      senderAccountId: sender.accountId,
      senderIdentity: sender.identity,
      recipientAccountId: recipient.accountId,
      recipientBundle: recipient.bundle,
      text: "tamper with me",
    });

    const tampered = drop.ciphertext.slice();
    tampered[Math.floor(tampered.length / 2)]! ^= 0xff;

    const result = await openLemonDrop({
      myIdentity: recipient.identity,
      mySignedPrekeys: recipient.signedPrekeys,
      myOneTimePrekeys: recipient.oneTimePrekeys,
      ciphertext: tampered,
    });
    expect(result.outcome).toBe("not-recipient");
  });

  it("works with no one-time prekey in the bundle", async () => {
    const sender = await makeSender();
    const recipient = await makeRecipient({ withOtp: false });
    expect(recipient.bundle.oneTimePrekey).toBeNull();

    const drop = await createLemonDrop({
      senderAccountId: sender.accountId,
      senderIdentity: sender.identity,
      recipientAccountId: recipient.accountId,
      recipientBundle: recipient.bundle,
      text: "no otp, still sealed",
    });

    const result = await openLemonDrop({
      myIdentity: recipient.identity,
      mySignedPrekeys: recipient.signedPrekeys,
      myOneTimePrekeys: recipient.oneTimePrekeys,
      ciphertext: drop.ciphertext,
    });
    expect(result.outcome).toBe("message");
    if (result.outcome !== "message") return;
    expect(result.text).toBe("no otp, still sealed");
    expect(result.usedOneTimePrekeyId).toBeNull();
  });

  it("opens a drop made against an older signed prekey after rotation", async () => {
    const sender = await makeSender();
    const recipient = await makeRecipient();

    // The creator fetched the bundle while spkOld was current; the recipient
    // has since rotated to spkNew but still retains both private halves.
    const spkOld: SignedPrekey = { ...recipient.signedPrekeys[0]!, createdAt: 1000 };
    const identity = recipient.identity;
    const fresh = await generateSignedPrekey(identity, 2);
    const spkNew: SignedPrekey = { ...fresh, createdAt: 2000 };

    const drop = await createLemonDrop({
      senderAccountId: sender.accountId,
      senderIdentity: sender.identity,
      recipientAccountId: recipient.accountId,
      recipientBundle: {
        identityKey: identity.publicKey,
        signedPrekey: { id: spkOld.id, publicKey: spkOld.publicKey, signature: spkOld.signature },
        oneTimePrekey: null,
      },
      text: "sent before you rotated",
    });

    const result = await openLemonDrop({
      myIdentity: identity,
      // Newest-first sort inside openLemonDrop must fall through spkNew to spkOld.
      mySignedPrekeys: [spkNew, spkOld],
      myOneTimePrekeys: [],
      ciphertext: drop.ciphertext,
    });
    expect(result.outcome).toBe("message");
    if (result.outcome !== "message") return;
    expect(result.text).toBe("sent before you rotated");
  });

  it("does not consume its inputs — the bundle drives two independent drops", async () => {
    const sender = await makeSender();
    const recipient = await makeRecipient();

    const first = await createLemonDrop({
      senderAccountId: sender.accountId,
      senderIdentity: sender.identity,
      recipientAccountId: recipient.accountId,
      recipientBundle: recipient.bundle,
      text: "first drop",
    });
    const second = await createLemonDrop({
      senderAccountId: sender.accountId,
      senderIdentity: sender.identity,
      recipientAccountId: recipient.accountId,
      recipientBundle: recipient.bundle,
      text: "second drop",
    });

    // Distinct ids/tokens, and each decrypts on its own.
    expect(first.qrId).not.toEqual(second.qrId);
    expect(first.burnHash).not.toEqual(second.burnHash);

    const openFirst = await openLemonDrop({
      myIdentity: recipient.identity,
      mySignedPrekeys: recipient.signedPrekeys,
      myOneTimePrekeys: recipient.oneTimePrekeys,
      ciphertext: first.ciphertext,
    });
    const openSecond = await openLemonDrop({
      myIdentity: recipient.identity,
      mySignedPrekeys: recipient.signedPrekeys,
      myOneTimePrekeys: recipient.oneTimePrekeys,
      ciphertext: second.ciphertext,
    });
    expect(openFirst.outcome === "message" && openFirst.text).toBe("first drop");
    expect(openSecond.outcome === "message" && openSecond.text).toBe("second drop");
  });
});
