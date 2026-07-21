// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// Sub-phase 5a: the OPEN half of Android-family drop CREATION. An Android/iOS
// creator's identity is a libsignal Curve25519 (Montgomery) key, not Ed25519.
// A drop it seals stamps `sender_key_family: "curve25519"` so the recipient DHs
// against that identity key VERBATIM (no Ed25519→Curve25519 conversion). Here
// a web recipient opens such a drop and proves the family switch is load-
// bearing: the identical bytes with the ed25519 default do NOT decrypt.
//
// The creation side is done here with a small TEST-ONLY helper that mirrors
// packages/crypto createLemonDrop step-for-step, but with a Montgomery sender
// and the family-aware DH. It is NOT wired into production createLemonDrop:
// web/desktop creation is always Ed25519, and real Android creation is Kotlin
// (sub-phase 5b). The 5b close-out regenerates a committed curve25519-sender
// cross-stack fixture so the Android JVM path gets the same positive coverage.

import { describe, expect, it } from "vitest";
import { sodium, ready } from "./sodium.js";
import { toBase64, utf8Encode } from "./encoding.js";
import {
  generateIdentityKeyPair,
  generateOneTimePrekeys,
  generateSignedPrekey,
  identityKeyToX25519,
  type IdentityKeyPair,
  type OneTimePrekey,
  type SignedPrekey,
} from "./keys.js";
import { x3dhInitiate, type DecodedPreKeyBundle } from "./x3dh.js";
import { ratchetEncrypt } from "./ratchet.js";
import { sealTo } from "./sealedbox.js";
import { pad } from "./padding.js";
import { openLemonDrop } from "./lemondrop.js";
import {
  PROTOCOL_VERSION,
  serializeLemonDrop,
  type MessageEnvelope,
  type SenderKeyFamily,
} from "@zitrone/protocol";

interface Recipient {
  accountId: string;
  identity: IdentityKeyPair;
  signedPrekeys: SignedPrekey[];
  oneTimePrekeys: OneTimePrekey[];
  bundle: DecodedPreKeyBundle;
}

// An ordinary web (Ed25519) recipient — it is the OPENER; the family under test
// is the SENDER's, not the recipient's.
async function makeRecipient(): Promise<Recipient> {
  const identity = await generateIdentityKeyPair();
  const signed = await generateSignedPrekey(identity, 1);
  const otps = await generateOneTimePrekeys(1);
  return {
    accountId: crypto.randomUUID(),
    identity,
    signedPrekeys: [signed],
    oneTimePrekeys: otps,
    bundle: {
      identityKey: identity.publicKey,
      signedPrekey: { id: signed.id, publicKey: signed.publicKey, signature: signed.signature },
      oneTimePrekey: { id: otps[0]!.id, publicKey: otps[0]!.publicKey },
    },
  };
}

/**
 * Seal a lemon drop from a Montgomery-identity (Android-like) sender, mirroring
 * createLemonDrop's steps exactly but choosing the payload's sender_key_family.
 * `family` is a parameter so one helper can produce both the correct
 * ("curve25519") drop and the mis-stamped ("ed25519") control that must fail.
 */
async function sealMontgomerySenderDrop(
  recipient: Recipient,
  text: string,
  family: SenderKeyFamily,
): Promise<{ ciphertext: Uint8Array; senderMontgomeryKey: Uint8Array; senderAccountId: string }> {
  await ready();
  // The sender's libsignal-style identity: a raw X25519/Montgomery keypair. Its
  // PUBLIC half is exactly what an Android creator uploads (raw 32 bytes, no DJB
  // type-prefix) and stamps into sender_identity_key. x3dhInitiate only reads
  // x25519PrivateKey for the DH, so the Ed25519 slots are placeholders here.
  const senderKp = sodium.crypto_box_keypair();
  const senderIdentity: IdentityKeyPair = {
    publicKey: senderKp.publicKey,
    privateKey: senderKp.privateKey,
    x25519PublicKey: senderKp.publicKey,
    x25519PrivateKey: senderKp.privateKey,
  };
  const senderAccountId = crypto.randomUUID();

  // The recipient bundle is Ed25519 (web) — ordinary, no cross-family opt-in.
  const init = await x3dhInitiate(senderIdentity, recipient.bundle);
  const encrypted = await ratchetEncrypt(init.session, await pad(utf8Encode(text)));

  const envelope: MessageEnvelope = {
    id: crypto.randomUUID(),
    sender_id: senderAccountId,
    recipient_id: recipient.accountId,
    ciphertext: await toBase64(encrypted.blob),
    ephemeral_key: await toBase64(init.ephemeralPublicKey),
    prekey_id: init.usedPrekeyId,
    message_number: encrypted.messageNumber,
    previous_chain_length: encrypted.previousChainLength,
    timestamp: new Date().toISOString(),
    ttl_seconds: null,
    burn_on_read: false,
    media_type: "text",
    version: PROTOCOL_VERSION,
  };

  const burnToken = sodium.randombytes_buf(32);
  const payloadBytes = utf8Encode(
    serializeLemonDrop({
      envelope,
      // The Montgomery public key, used verbatim by the family-aware responder.
      senderIdentityKey: await toBase64(senderKp.publicKey),
      burnToken: await toBase64(burnToken),
      senderKeyFamily: family,
    }),
  );

  // Recipient is Ed25519 → seal to its converted X25519 point (unchanged path).
  const recipientX25519 = await identityKeyToX25519(recipient.bundle.identityKey);
  const ciphertext = await pad(await sealTo(recipientX25519, payloadBytes));
  return { ciphertext, senderMontgomeryKey: senderKp.publicKey, senderAccountId };
}

describe("lemon drops from a Curve25519 (Montgomery) sender", () => {
  it("a web recipient opens a curve25519-family drop and recovers the Montgomery sender key", async () => {
    const recipient = await makeRecipient();
    const drop = await sealMontgomerySenderDrop(recipient, "sealed by a mobile hand", "curve25519");

    const result = await openLemonDrop({
      myIdentity: recipient.identity,
      mySignedPrekeys: recipient.signedPrekeys,
      myOneTimePrekeys: recipient.oneTimePrekeys,
      ciphertext: drop.ciphertext,
    });

    expect(result.outcome).toBe("message");
    if (result.outcome !== "message") return;
    expect(result.text).toBe("sealed by a mobile hand");
    expect(result.senderAccountId).toBe(drop.senderAccountId);
    // The family is surfaced so the redeem layer skips the impossible ordinary
    // cross-family session and stores a session-less contact instead.
    expect(result.senderKeyFamily).toBe("curve25519");
    // The recovered claimed key is the raw Montgomery key verbatim — exactly the
    // form the recipient pins/compares as base64 (no conversion either side).
    expect(result.senderIdentityKey).toEqual(drop.senderMontgomeryKey);
  });

  it("the same bytes stamped ed25519 do NOT decrypt — the family switch is load-bearing", async () => {
    // Identical construction, but the payload lies (or omits → defaults) that the
    // Montgomery sender key is Ed25519. The responder then runs it through the
    // Edwards→Montgomery map, derives a garbage DH1, and cannot decrypt. The seal
    // still opens (addressed to us), so the honest outcome is "invalid", never
    // "not-recipient": our drop, gone wrong.
    const recipient = await makeRecipient();
    const drop = await sealMontgomerySenderDrop(recipient, "must not open as ed25519", "ed25519");

    const result = await openLemonDrop({
      myIdentity: recipient.identity,
      mySignedPrekeys: recipient.signedPrekeys,
      myOneTimePrekeys: recipient.oneTimePrekeys,
      ciphertext: drop.ciphertext,
    });
    expect(result.outcome).toBe("invalid");
  });
});
