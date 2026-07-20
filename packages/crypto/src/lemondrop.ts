// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

/**
 * "Lemon drops" — the crypto for QR dead drops. A lemon drop is a message
 * encrypted ONCE, at creation time, to a single named recipient, with no live
 * session on either side:
 *
 *   1. one-shot X3DH against the recipient's published prekey bundle yields a
 *      session used for EXACTLY ONE ratchet message — the padded text — and
 *      then thrown away (encrypt-and-forget);
 *   2. the message envelope, the sender's identity key, and a fresh burn token
 *      are sealed to the recipient's identity key, so the relay and any
 *      wrong-recipient scanner see only an opaque, fixed-block box;
 *   3. the sealed box is deposited under a random qr_id, admitted by a hashcash
 *      proof-of-work, with SHA-256(burn_token) left alongside so that only a
 *      client who decrypts the payload — and thereby recovers the token — can
 *      later burn the drop.
 *
 * Reading a drop is the mirror image: open the seal (failure = "not for you"),
 * parse the payload, then run a ONE-OFF X3DH responder session to decrypt the
 * envelope. Both sessions here are ephemeral and MUST NEVER touch, advance, or
 * be confused with any persistent Double Ratchet session a client keeps for an
 * ongoing conversation with the same peer. This module holds no state and does
 * no I/O; the caller deposits, fetches, and burns.
 */

import { sodium, ready } from "./sodium.js";
import { fromBase64, toBase64, utf8Decode, utf8Encode } from "./encoding.js";
import {
  identityKeyToX25519,
  type IdentityKeyPair,
  type OneTimePrekey,
  type SignedPrekey,
} from "./keys.js";
import { x3dhInitiate, x3dhRespond, type DecodedPreKeyBundle } from "./x3dh.js";
import { ratchetDecrypt, ratchetEncrypt } from "./ratchet.js";
import { openSealed, sealTo } from "./sealedbox.js";
import { pad, unpad } from "./padding.js";
import { solveProofOfWork } from "./deaddrop.js";
import {
  buildQrDropUrl,
  DROP_POW_DIFFICULTY,
  PROTOCOL_VERSION,
  QR_DROP_BURN_TOKEN_BYTES,
  QR_DROP_ID_BYTES,
  serializeLemonDrop,
  parseLemonDrop,
  type MessageEnvelope,
} from "@zitrone/protocol";

export interface CreateLemonDropInput {
  /** Creator's account UUID — the envelope's sender_id. */
  senderAccountId: string;
  senderIdentity: IdentityKeyPair;
  /** Recipient's account UUID — the envelope's recipient_id. */
  recipientAccountId: string;
  /** Recipient's decoded prekey bundle (fetched from the server, verified in X3DH). */
  recipientBundle: DecodedPreKeyBundle;
  text: string;
}

export interface CreateLemonDropResult {
  /** 16 random bytes. The caller base64url-encodes this for the wire qr_id. */
  qrId: Uint8Array;
  /** `https://zitrone.app/d/{qr_id}` — the string the QR sticker encodes. */
  url: string;
  /** Padded sealed box. The caller base64-encodes this as the deposit ciphertext. */
  ciphertext: Uint8Array;
  /** SHA-256(burn_token) — deposited as burn_hash so only a decryptor can burn. */
  burnHash: Uint8Array;
  /** 8-byte hashcash nonce solving the PoW over qrId. */
  powNonce: Uint8Array;
}

/**
 * Create a lemon drop addressed to one recipient. Returns everything the caller
 * needs to deposit and to print the sticker; nothing here is persisted and the
 * ephemeral X3DH session is discarded before returning.
 */
export async function createLemonDrop(input: CreateLemonDropInput): Promise<CreateLemonDropResult> {
  await ready();
  const { senderAccountId, senderIdentity, recipientAccountId, recipientBundle, text } = input;

  // One-shot X3DH against the recipient's bundle. The session this returns
  // encrypts EXACTLY ONE message and is then dropped on the floor: a lemon
  // drop is deliberately encrypt-and-forget. This session must never be
  // persisted, reused, or mistaken for a live contact session the sender may
  // separately keep with this recipient — reuse would fork ratchet state.
  // allowCrossFamily: a lemon drop is the ONE path that may address a mobile
  // (Curve25519) recipient — the drop is a one-shot sealed payload the mobile
  // side opens with a matching one-shot responder, never an ongoing session
  // (contrast ordinary messaging, which x3dhInitiate refuses for mobile
  // bundles by default). See X3DHInitiateOptions.
  const init = await x3dhInitiate(senderIdentity, recipientBundle, { allowCrossFamily: true });
  const encrypted = await ratchetEncrypt(init.session, await pad(utf8Encode(text)));
  // init.session goes out of scope here, unpersisted — by design.

  // The envelope is an X3DH INITIAL message: it carries the ephemeral key and
  // (when one was available) the consumed one-time prekey id, exactly as a
  // first message would. Fields mirror apps/web sendDeadDrop's construction.
  const envelope: MessageEnvelope = {
    id: crypto.randomUUID(),
    sender_id: senderAccountId,
    recipient_id: recipientAccountId,
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

  const burnToken = sodium.randombytes_buf(QR_DROP_BURN_TOKEN_BYTES);
  const qrId = sodium.randombytes_buf(QR_DROP_ID_BYTES);

  const payloadBytes = utf8Encode(
    serializeLemonDrop({
      envelope,
      senderIdentityKey: await toBase64(senderIdentity.publicKey),
      burnToken: await toBase64(burnToken),
    }),
  );

  // Seal the whole payload (envelope + sender identity + burn token) to the
  // recipient's identity key, then pad to a fixed block: the relay and any
  // wrong-recipient scanner see only opaque, length-uninformative bytes.
  // Family-aware, using the SAME discrimination x3dhInitiate just made from
  // signature verification: an Android/iOS identity key is already the X25519
  // point the seal targets; an Ed25519 (web/desktop) key converts via the
  // birational map. Mixing these up would seal to a key nobody holds.
  const recipientX25519 =
    init.identityKeyFamily === "curve25519"
      ? recipientBundle.identityKey
      : await identityKeyToX25519(recipientBundle.identityKey);
  const ciphertext = await pad(await sealTo(recipientX25519, payloadBytes));

  // Admission control identical to dead drops: hashcash over the qr_id, so the
  // work is bound to this deposit and cannot be precomputed or reused. Deposit
  // is unauthenticated; the PoW is the only cost.
  const powNonce = await solveProofOfWork(qrId, DROP_POW_DIFFICULTY);
  const burnHash = sodium.crypto_hash_sha256(burnToken);

  return { qrId, url: buildQrDropUrl(qrId), ciphertext, burnHash, powNonce };
}

export interface OpenLemonDropInput {
  myIdentity: IdentityKeyPair;
  /** All of my current signed prekeys — tried newest-first (the creator may
   *  have fetched my bundle before a rotation). */
  mySignedPrekeys: SignedPrekey[];
  /** My one-time prekeys — the one the creator consumed is selected by id. */
  myOneTimePrekeys: OneTimePrekey[];
  /** The fetched drop blob (padded sealed box). */
  ciphertext: Uint8Array;
}

export type LemonDropOpenResult =
  | { outcome: "not-recipient" }
  | { outcome: "invalid" }
  | {
      outcome: "message";
      text: string;
      /** The envelope's claimed sender account UUID. */
      senderAccountId: string;
      /**
       * The sender's CLAIMED identity key from inside the sealed payload —
       * Ed25519 for a web/desktop creator, Curve25519 (Montgomery) for an
       * Android/iOS creator (see the payload's sender_key_family). TRUST
       * BOUNDARY: opening the seal proves the box was addressed to us, NOT who
       * wrote it. The caller MUST cross-check this against any existing contact
       * record for senderAccountId before trusting the sender. The pinned
       * contact key is the same wire form (a mobile contact's key is stored as
       * its raw 32-byte Curve25519 wire key), so the base64 compare is direct.
       */
      senderIdentityKey: Uint8Array;
      /** The recovered 32-byte burn token — present it to burn the drop. */
      burnToken: Uint8Array;
      /** The one-time prekey id consumed, or null. The caller deletes its
       *  private half — one-time prekeys are single-use by design. */
      usedOneTimePrekeyId: number | null;
    };

/**
 * Try to open a fetched lemon drop as its intended recipient. Three outcomes,
 * kept honestly distinct at the crypto layer even if the UI collapses them:
 *
 *   - "not-recipient": the seal did not open (any failure — wrong key, corrupt
 *     padding, truncation). Indistinguishable, and correct: this drop is not
 *     for us and we cannot tell it apart from garbage.
 *   - "invalid": the seal DID open — the box WAS addressed to us — but the
 *     payload is malformed or the inner ciphertext will not decrypt. This is
 *     our drop, gone wrong; it is a lie to report it as someone else's.
 *   - "message": decrypted text plus the metadata needed to display and burn.
 */
export async function openLemonDrop(input: OpenLemonDropInput): Promise<LemonDropOpenResult> {
  await ready();
  const { myIdentity, mySignedPrekeys, myOneTimePrekeys, ciphertext } = input;

  // Unpad + open the seal. ANY failure here means "not for you" — and we must
  // not leak which failure it was: a wrong-recipient scan and a corrupt blob
  // look identical from the outside.
  let payloadBytes: Uint8Array;
  try {
    const sealed = unpad(ciphertext);
    payloadBytes = await openSealed(
      myIdentity.x25519PublicKey,
      myIdentity.x25519PrivateKey,
      sealed,
    );
  } catch {
    return { outcome: "not-recipient" };
  }

  // From here the box WAS ours (the seal opened). A parse failure is therefore
  // "invalid" (our malformed drop), NOT "not-recipient" — the crypto layer must
  // not claim a box we opened belongs to someone else.
  const payload = parseLemonDrop(utf8Decode(payloadBytes));
  if (!payload) return { outcome: "invalid" };

  const { envelope } = payload;
  if (!envelope.ephemeral_key) return { outcome: "invalid" };

  let senderIdentityKey: Uint8Array;
  let burnToken: Uint8Array;
  let ephemeralKey: Uint8Array;
  let envelopeCiphertext: Uint8Array;
  try {
    senderIdentityKey = await fromBase64(payload.sender_identity_key);
    burnToken = await fromBase64(payload.burn_token);
    ephemeralKey = await fromBase64(envelope.ephemeral_key);
    envelopeCiphertext = await fromBase64(envelope.ciphertext);
  } catch {
    return { outcome: "invalid" };
  }

  // Select the one-time prekey the initiator consumed. If they named an id we
  // no longer hold, usedOtp is null: the responder DH will simply not
  // reconstruct and decrypt will fail below → "invalid".
  const usedOtp =
    envelope.prekey_id == null
      ? null
      : (myOneTimePrekeys.find((p) => p.id === envelope.prekey_id) ?? null);

  // Try our signed prekeys newest-first — the initiator used whichever was
  // current when they fetched our bundle, which may since have rotated. Same
  // retry shape as apps/web respondToInitialMessage.
  // Sender-family awareness: a "curve25519" drop (an Android/iOS creator) carries
  // an identity key that ALREADY IS the Montgomery point the responder DH needs,
  // so the responder must skip the Ed25519→Curve25519 conversion. Absent/"ed25519"
  // (every web/desktop-created drop) keeps the existing convert-then-DH path.
  const senderIdentityIsMontgomery = payload.sender_key_family === "curve25519";
  const spks = [...mySignedPrekeys].sort((a, b) => b.createdAt - a.createdAt);
  for (const spk of spks) {
    try {
      // One-off responder session for exactly this drop — never persisted,
      // never reused, discarded the moment we hold the plaintext.
      const session = await x3dhRespond(myIdentity, spk, usedOtp, senderIdentityKey, ephemeralKey, {
        senderIdentityIsMontgomery,
      });
      const padded = await ratchetDecrypt(session, {
        blob: envelopeCiphertext,
        messageNumber: envelope.message_number,
        previousChainLength: envelope.previous_chain_length,
      });
      const text = utf8Decode(unpad(padded));
      return {
        outcome: "message",
        text,
        senderAccountId: envelope.sender_id,
        senderIdentityKey,
        burnToken,
        usedOneTimePrekeyId: usedOtp?.id ?? null,
      };
    } catch {
      // Wrong signed prekey (or genuinely undecryptable) — try the next.
    }
  }

  // Sealed to us and well-formed, but no signed prekey decrypts it.
  return { outcome: "invalid" };
}
