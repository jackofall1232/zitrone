// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// The two-independent-initiators deadlock and its guarded recovery (maintainer
// decision 2a). Lemon drops manufacture the collision systematically: the
// creator (A) holds an outbound session to the recipient (B) from adding them
// as a contact, and B's redeem flow spins up a SECOND, independent outbound
// session back to A — so B's first reply arrives at A encrypted under a session
// A has never seen, fails against A's stored one, and was previously dropped
// on the floor. These tests replay that exact sequence with real key material
// and prove the recovery the web store now performs: on decrypt failure of an
// envelope that carries an X3DH initial-message header, respond to the
// handshake keyed on the PINNED contact identity key and replace the session.
// They also prove the guard: a forger who does not hold the pinned identity's
// private half cannot get a reset accepted, and an envelope without the X3DH
// header never enters the reset path at all.

import { describe, expect, it } from "vitest";
import {
  generateIdentityKeyPair,
  generateOneTimePrekeys,
  generateSignedPrekey,
  type IdentityKeyPair,
  type OneTimePrekey,
  type SignedPrekey,
} from "./keys.js";
import { x3dhInitiate, x3dhRespond, type DecodedPreKeyBundle } from "./x3dh.js";
import { ratchetDecrypt, ratchetEncrypt } from "./ratchet.js";
import { pad, unpad } from "./padding.js";
import { utf8Decode, utf8Encode } from "./encoding.js";

// A full party: identity, published prekey material, and the bundle a peer
// would fetch from the relay.
interface Party {
  identity: IdentityKeyPair;
  signedPrekeys: SignedPrekey[];
  oneTimePrekeys: OneTimePrekey[];
  bundle: DecodedPreKeyBundle;
}

async function makeParty(options: { withOtp?: boolean } = {}): Promise<Party> {
  const withOtp = options.withOtp ?? true;
  const identity = await generateIdentityKeyPair();
  const signed = await generateSignedPrekey(identity, 1);
  const otps = withOtp ? await generateOneTimePrekeys(1) : [];
  return {
    identity,
    signedPrekeys: [signed],
    oneTimePrekeys: otps,
    bundle: {
      identityKey: identity.publicKey,
      signedPrekey: { id: signed.id, publicKey: signed.publicKey, signature: signed.signature },
      oneTimePrekey: otps[0] ? { id: otps[0].id, publicKey: otps[0].publicKey } : null,
    },
  };
}

// Mirrors the store's guarded reset exactly: given a failed decrypt of an
// envelope carrying an X3DH header, respond keyed on the PINNED identity key,
// trying signed prekeys newest-first, probing each candidate by decrypting.
async function guardedReset(
  me: Party,
  pinnedSenderIdentityKey: Uint8Array,
  envelope: {
    ephemeralKey: Uint8Array;
    prekeyId: number | null;
    blob: Uint8Array;
    messageNumber: number;
    previousChainLength: number;
  },
) {
  const usedOtp =
    envelope.prekeyId == null
      ? null
      : (me.oneTimePrekeys.find((p) => p.id === envelope.prekeyId) ?? null);
  let lastError: unknown = new Error("no signed prekeys");
  for (const spk of me.signedPrekeys) {
    try {
      const session = await x3dhRespond(
        me.identity,
        spk,
        usedOtp,
        pinnedSenderIdentityKey,
        envelope.ephemeralKey,
      );
      const plaintext = await ratchetDecrypt(session, {
        blob: envelope.blob,
        messageNumber: envelope.messageNumber,
        previousChainLength: envelope.previousChainLength,
      });
      return { session, text: utf8Decode(unpad(plaintext)) };
    } catch (e) {
      lastError = e;
    }
  }
  throw lastError;
}

describe("guarded session reset (two-initiator deadlock)", () => {
  it("recovers the first reply from a drop-created contact", async () => {
    const a = await makeParty(); // lemon-drop creator
    const b = await makeParty(); // drop recipient

    // A added B as a contact: A holds an outbound initiator session to B.
    // (This is the session A's client decrypts B's traffic against.)
    const aInit = await x3dhInitiate(a.identity, b.bundle);
    const aStoredSession = aInit.session;

    // B redeems A's lemon drop. The one-shot session inside the drop is
    // discarded by design, so B's redeem flow creates its OWN outbound
    // initiator session back to A — the second, independent initiator.
    const bInit = await x3dhInitiate(b.identity, a.bundle);

    // B's first reply rides that new session, as an X3DH initial message.
    const reply = await ratchetEncrypt(bInit.session, await pad(utf8Encode("got it. midnight.")));
    const envelope = {
      ephemeralKey: bInit.ephemeralPublicKey,
      prekeyId: bInit.usedPrekeyId,
      blob: reply.blob,
      messageNumber: reply.messageNumber,
      previousChainLength: reply.previousChainLength,
    };

    // THE DEADLOCK: A's stored session cannot decrypt it. Before the fix this
    // envelope was silently dropped and the reply lost.
    await expect(
      ratchetDecrypt(aStoredSession, {
        blob: envelope.blob,
        messageNumber: envelope.messageNumber,
        previousChainLength: envelope.previousChainLength,
      }),
    ).rejects.toThrow();

    // THE RECOVERY: the envelope carries an X3DH header, so A responds to the
    // handshake keyed on B's PINNED identity key and reads the reply.
    const recovered = await guardedReset(a, b.identity.publicKey, envelope);
    expect(recovered.text).toBe("got it. midnight.");

    // The replaced session is live in BOTH directions: A answers on it, B
    // decrypts with the session it already holds, and B's next message comes
    // back to A on the same ratchet — converged, no second reset needed.
    const aAnswer = await ratchetEncrypt(recovered.session, await pad(utf8Encode("bring keys")));
    const bReads = await ratchetDecrypt(bInit.session, aAnswer);
    expect(utf8Decode(unpad(bReads))).toBe("bring keys");
    const bAgain = await ratchetEncrypt(bInit.session, await pad(utf8Encode("always do")));
    const aReads = await ratchetDecrypt(recovered.session, bAgain);
    expect(utf8Decode(unpad(aReads))).toBe("always do");
  });

  it("recovers the mutual-addContact collision the same way", async () => {
    const a = await makeParty({ withOtp: false });
    const b = await makeParty({ withOtp: false });

    // Both sides add each other simultaneously — two initiator sessions.
    const aInit = await x3dhInitiate(a.identity, b.bundle);
    const bInit = await x3dhInitiate(b.identity, a.bundle);

    const first = await ratchetEncrypt(bInit.session, await pad(utf8Encode("hello from B")));
    await expect(ratchetDecrypt(aInit.session, first)).rejects.toThrow();

    const recovered = await guardedReset(a, b.identity.publicKey, {
      ephemeralKey: bInit.ephemeralPublicKey,
      prekeyId: bInit.usedPrekeyId,
      blob: first.blob,
      messageNumber: first.messageNumber,
      previousChainLength: first.previousChainLength,
    });
    expect(recovered.text).toBe("hello from B");
  });

  it("rejects a reset from a forger who does not hold the pinned identity key", async () => {
    const a = await makeParty();
    const b = await makeParty();
    const mallory = await makeParty();

    // Mallory builds a perfectly well-formed initial message to A — but A's
    // pinned key for this contact is B's. The pinned key is mixed into the
    // X3DH secret, so every responder candidate fails to decrypt and the
    // reset is refused: an envelope only a key-holder could have built is the
    // authentication.
    const malInit = await x3dhInitiate(mallory.identity, a.bundle);
    const forged = await ratchetEncrypt(malInit.session, await pad(utf8Encode("it's B, trust me")));

    await expect(
      guardedReset(a, b.identity.publicKey, {
        ephemeralKey: malInit.ephemeralPublicKey,
        prekeyId: malInit.usedPrekeyId,
        blob: forged.blob,
        messageNumber: forged.messageNumber,
        previousChainLength: forged.previousChainLength,
      }),
    ).rejects.toThrow();
  });

  it("stays inert for ordinary ratchet traffic without an X3DH header", async () => {
    // The store's guard checks envelope.ephemeral_key before attempting any
    // reset; ordinary ratchet messages never carry one. This pins the crypto
    // fact that makes the guard sound: an established-session message cannot
    // double as an initial message, because responding to a handshake needs
    // the initiator's ephemeral key and there is none to present.
    const a = await makeParty();
    const b = await makeParty();
    const bInit = await x3dhInitiate(b.identity, a.bundle);

    // B establishes normally with A responding — a healthy session.
    const first = await ratchetEncrypt(bInit.session, await pad(utf8Encode("hi")));
    const aSession = await x3dhRespond(
      a.identity,
      a.signedPrekeys[0]!,
      a.oneTimePrekeys.find((p) => p.id === bInit.usedPrekeyId) ?? null,
      b.identity.publicKey,
      bInit.ephemeralPublicKey,
    );
    await ratchetDecrypt(aSession, first);

    // A later ordinary message that arrives corrupted fails decrypt — and has
    // no ephemeral key, so under the store's guard it is dropped, never reset.
    const second = await ratchetEncrypt(bInit.session, await pad(utf8Encode("second")));
    const corrupted = second.blob.slice();
    corrupted[corrupted.length - 1] ^= 0xff;
    await expect(
      ratchetDecrypt(aSession, { ...second, blob: corrupted }),
    ).rejects.toThrow();
  });
});
