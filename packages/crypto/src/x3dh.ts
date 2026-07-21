// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { sodium, ready } from "./sodium.js";
import { concat } from "./encoding.js";
import { hkdf } from "./kdf.js";
import {
  classifyBundleIdentity,
  identityKeyToX25519,
  type IdentityKeyFamily,
  type IdentityKeyPair,
  type OneTimePrekey,
  type SignedPrekey,
} from "./keys.js";
import { initRatchetAsInitiator, initRatchetAsResponder, type RatchetSession } from "./ratchet.js";

const X3DH_INFO = "Zitrone-X3DH-v1";

/** Peer's prekey bundle, decoded to raw bytes (fetched from the server). */
export interface DecodedPreKeyBundle {
  /** Public identity key — Ed25519 (web/desktop) or Curve25519 Montgomery
   *  (Android/iOS); which one is decided by signature verification, never
   *  assumed (see [classifyBundleIdentity]). */
  identityKey: Uint8Array;
  signedPrekey: { id: number; publicKey: Uint8Array; signature: Uint8Array };
  oneTimePrekey: { id: number; publicKey: Uint8Array } | null;
}

export interface X3DHInitiationResult {
  session: RatchetSession;
  /** Send in the envelope's ephemeral_key field on the first message */
  ephemeralPublicKey: Uint8Array;
  /** Send in the envelope's prekey_id field, null if no OPK was available */
  usedPrekeyId: number | null;
  /** Which family the bundle's identity key verified under — callers that
   *  encrypt TO the identity key (sealed boxes) need the same discrimination
   *  this function used for the DH step. */
  identityKeyFamily: IdentityKeyFamily;
}

/**
 * X3DH as the session initiator (Alice). Verifies the signed prekey's
 * signature before any DH — an unverified bundle is rejected outright.
 *
 *   DH1 = DH(IK_A,  SPK_B)
 *   DH2 = DH(EK_A,  IK_B)
 *   DH3 = DH(EK_A,  SPK_B)
 *   DH4 = DH(EK_A,  OPK_B)        — when a one-time prekey is available
 *   SK  = HKDF(F || DH1..DH4)     — F = 32 bytes of 0xFF (per the X3DH spec)
 */
export interface X3DHInitiateOptions {
  /**
   * Accept a Curve25519 (Android/iOS libsignal) bundle in addition to a
   * web/desktop Ed25519 one. Defaults to FALSE, and that default is load-
   * bearing: ordinary messaging (`addContact` → `sendMessage`) produces this
   * package's custom ratchet blob, which a mobile client's libsignal decrypt
   * path cannot parse — so a cross-family "contact" would send messages that
   * never decrypt. Only **lemon-drop creation** sets this true, because a drop
   * is a one-shot sealed payload the mobile side opens with a matching one-shot
   * responder (apps/android LemonDropOneShot), never an ongoing session. Keep
   * these two callers the ONLY place this is enabled.
   */
  allowCrossFamily?: boolean;
}

export async function x3dhInitiate(
  myIdentityKey: IdentityKeyPair,
  theirPreKeyBundle: DecodedPreKeyBundle,
  options: X3DHInitiateOptions = {},
): Promise<X3DHInitiationResult> {
  await ready();
  const { identityKey, signedPrekey, oneTimePrekey } = theirPreKeyBundle;

  // Try-both verification, the client-side mirror of the relay's dual-scheme
  // verifySignedPrekey (handlers.go): plain Ed25519 over the raw prekey
  // (web/desktop bundles) or XEdDSA over the 33-byte tagged form (Android/iOS
  // bundles). FAIL CLOSED — a bundle that verifies under neither scheme is
  // rejected outright, exactly as before this became family-aware.
  const family = await classifyBundleIdentity(
    signedPrekey.publicKey,
    signedPrekey.signature,
    identityKey,
  );
  if (family === null) throw new Error("prekey bundle signature verification failed");

  // A Curve25519 (mobile-family) bundle is only usable by a caller that opted
  // in — see X3DHInitiateOptions.allowCrossFamily. Ordinary messaging leaves
  // it false, so this restores the pre-family-aware behavior for that path: a
  // mobile bundle is refused here rather than seeding a session whose messages
  // the peer could never decrypt.
  if (family === "curve25519" && !options.allowCrossFamily) {
    throw new Error("cross-family bundle not supported for ordinary messaging");
  }

  // An Ed25519 identity key participates in DH via the birational map; a
  // Curve25519 (Android/iOS) identity key already IS the X25519 point and is
  // used verbatim — converting it as-if-Edwards would derive garbage.
  const theirIdentityX =
    family === "curve25519" ? identityKey : await identityKeyToX25519(identityKey);
  const ephemeral = sodium.crypto_box_keypair();

  const dh1 = sodium.crypto_scalarmult(myIdentityKey.x25519PrivateKey, signedPrekey.publicKey);
  const dh2 = sodium.crypto_scalarmult(ephemeral.privateKey, theirIdentityX);
  const dh3 = sodium.crypto_scalarmult(ephemeral.privateKey, signedPrekey.publicKey);
  const parts = [new Uint8Array(32).fill(0xff), dh1, dh2, dh3];
  if (oneTimePrekey) {
    parts.push(sodium.crypto_scalarmult(ephemeral.privateKey, oneTimePrekey.publicKey));
  }

  const sk = await hkdf(concat(...parts), new Uint8Array(32), X3DH_INFO, 32);
  zero(dh1, dh2, dh3, ephemeral.privateKey);

  const session = await initRatchetAsInitiator(sk, signedPrekey.publicKey);
  return {
    session,
    ephemeralPublicKey: ephemeral.publicKey,
    usedPrekeyId: oneTimePrekey?.id ?? null,
    identityKeyFamily: family,
  };
}

export interface X3DHRespondOptions {
  /**
   * The peer identity key handed in is ALREADY an X25519/Montgomery point, so
   * skip [identityKeyToX25519] and DH against it verbatim. Defaults FALSE, and
   * that default is the byte-identical existing path: an Ed25519 (web/desktop)
   * sender identity is converted via the birational map first. Set true ONLY
   * by the lemon-drop opener when the sealed payload's `sender_key_family` is
   * `"curve25519"` — an Android/iOS creator's identity key IS the Montgomery
   * point already, and converting it as-if-Edwards would derive garbage so no
   * drop would decrypt. Ordinary messaging (`respondToInitialMessage`) never
   * sets this, so its responder DH is completely unchanged.
   */
  senderIdentityIsMontgomery?: boolean;
}

/**
 * X3DH as the responder (Bob), on receiving an initial message that carries
 * the initiator's ephemeral key. The consumed one-time prekey's private half
 * must be deleted by the caller after this returns — it is single-use.
 */
export async function x3dhRespond(
  myIdentityKey: IdentityKeyPair,
  mySignedPrekey: SignedPrekey,
  myOneTimePrekey: OneTimePrekey | null,
  theirIdentityKey: Uint8Array,
  theirEphemeralKey: Uint8Array,
  options: X3DHRespondOptions = {},
): Promise<RatchetSession> {
  await ready();
  // Family-aware: a Curve25519 (mobile) sender identity is already the X25519
  // point we DH against; an Ed25519 (web/desktop) one converts via the
  // birational map. The default (options omitted → false) is the pre-family
  // behavior, so live messaging is byte-for-byte unaffected.
  const theirIdentityX = options.senderIdentityIsMontgomery
    ? theirIdentityKey
    : await identityKeyToX25519(theirIdentityKey);

  const dh1 = sodium.crypto_scalarmult(mySignedPrekey.privateKey, theirIdentityX);
  const dh2 = sodium.crypto_scalarmult(myIdentityKey.x25519PrivateKey, theirEphemeralKey);
  const dh3 = sodium.crypto_scalarmult(mySignedPrekey.privateKey, theirEphemeralKey);
  const parts = [new Uint8Array(32).fill(0xff), dh1, dh2, dh3];
  if (myOneTimePrekey) {
    parts.push(sodium.crypto_scalarmult(myOneTimePrekey.privateKey, theirEphemeralKey));
  }

  const sk = await hkdf(concat(...parts), new Uint8Array(32), X3DH_INFO, 32);
  zero(dh1, dh2, dh3);

  return initRatchetAsResponder(sk, {
    publicKey: mySignedPrekey.publicKey,
    privateKey: mySignedPrekey.privateKey,
  });
}

function zero(...buffers: Uint8Array[]): void {
  for (const b of buffers) sodium.memzero(b);
}
