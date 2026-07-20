// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { sodium, ready } from "./sodium.js";
import { signedPrekeyMessage, verifyXEdDSA } from "./xeddsa.js";

/**
 * The identity key is an Ed25519 keypair (so it can sign prekeys); its X25519
 * form — derived via the birational map — is what participates in X3DH DH
 * computations. The Ed25519 public key is what gets published and fingerprinted.
 */
export interface IdentityKeyPair {
  /** Ed25519 public key — published to the server */
  publicKey: Uint8Array;
  /** Ed25519 secret key — never leaves the device */
  privateKey: Uint8Array;
  /** X25519 form, used for DH */
  x25519PublicKey: Uint8Array;
  x25519PrivateKey: Uint8Array;
}

export interface SignedPrekey {
  id: number;
  publicKey: Uint8Array;
  privateKey: Uint8Array;
  /** Ed25519 signature of publicKey by the identity key */
  signature: Uint8Array;
  createdAt: number;
}

export interface OneTimePrekey {
  id: number;
  publicKey: Uint8Array;
  privateKey: Uint8Array;
}

export async function generateIdentityKeyPair(): Promise<IdentityKeyPair> {
  await ready();
  const ed = sodium.crypto_sign_keypair();
  return {
    publicKey: ed.publicKey,
    privateKey: ed.privateKey,
    x25519PublicKey: sodium.crypto_sign_ed25519_pk_to_curve25519(ed.publicKey),
    x25519PrivateKey: sodium.crypto_sign_ed25519_sk_to_curve25519(ed.privateKey),
  };
}

/** Convert a peer's published Ed25519 identity key to its X25519 form for DH. */
export async function identityKeyToX25519(edPublicKey: Uint8Array): Promise<Uint8Array> {
  await ready();
  return sodium.crypto_sign_ed25519_pk_to_curve25519(edPublicKey);
}

export async function generateSignedPrekey(
  identityKey: IdentityKeyPair,
  id: number,
): Promise<SignedPrekey> {
  await ready();
  const kp = sodium.crypto_box_keypair();
  return {
    id,
    publicKey: kp.publicKey,
    privateKey: kp.privateKey,
    signature: sodium.crypto_sign_detached(kp.publicKey, identityKey.privateKey),
    createdAt: Date.now(),
  };
}

export async function verifySignedPrekey(
  prekeyPublic: Uint8Array,
  signature: Uint8Array,
  identityPublicKey: Uint8Array,
): Promise<boolean> {
  await ready();
  return sodium.crypto_sign_verify_detached(signature, prekeyPublic, identityPublicKey);
}

/**
 * Which crypto family a published identity key belongs to — the two (scheme,
 * message-framing) pairs actually in use across shipped clients, coupled per
 * platform exactly as the relay's `verifySignedPrekey` (handlers.go) accepts
 * them:
 *
 *  - `"ed25519"`   — web/desktop (this package): a genuine Ed25519 identity
 *    key, plain-Ed25519-signing the raw 32-byte prekey directly.
 *  - `"curve25519"` — Android/iOS (libsignal-client): a Curve25519 (Montgomery)
 *    identity key, XEdDSA-signing the 33-byte type-tagged serialize() form.
 *
 * The family decides the identity key's DH handling downstream: an Ed25519 key
 * must be converted via the birational map ([identityKeyToX25519]) while a
 * Curve25519 key already IS the X25519 point and must be used verbatim —
 * running it through the Edwards conversion would silently derive garbage.
 */
export type IdentityKeyFamily = "ed25519" | "curve25519";

/**
 * Classify a fetched bundle's identity key by which signature convention its
 * signed prekey actually verifies under — the client-side port of the relay's
 * try-both `verifySignedPrekey` (handlers.go, ledger Run 12–14). FAILS CLOSED:
 * a bundle that verifies under neither real scheme returns null and must be
 * rejected outright, never guessed at.
 */
export async function classifyBundleIdentity(
  prekeyPublic: Uint8Array,
  signature: Uint8Array,
  identityPublicKey: Uint8Array,
): Promise<IdentityKeyFamily | null> {
  await ready();
  // Guard the plain-Ed25519 branch: libsodium THROWS on malformed key or
  // signature lengths where the Go server returns false; either way the
  // XEdDSA branch must still be tried.
  try {
    if (sodium.crypto_sign_verify_detached(signature, prekeyPublic, identityPublicKey)) {
      return "ed25519";
    }
  } catch {
    // fall through to the XEdDSA branch
  }
  if (await verifyXEdDSA(identityPublicKey, signedPrekeyMessage(prekeyPublic), signature)) {
    return "curve25519";
  }
  return null;
}

/** One-time prekeys are single-use by design — the private half is deleted after consumption. */
export async function generateOneTimePrekeys(count: number, startId = 1): Promise<OneTimePrekey[]> {
  await ready();
  return Array.from({ length: count }, (_, i) => {
    const kp = sodium.crypto_box_keypair();
    return { id: startId + i, publicKey: kp.publicKey, privateKey: kp.privateKey };
  });
}

/** Sign an arbitrary message with the Ed25519 identity key (login challenges). */
export async function signWithIdentity(
  message: Uint8Array,
  identityKey: IdentityKeyPair,
): Promise<Uint8Array> {
  await ready();
  return sodium.crypto_sign_detached(message, identityKey.privateKey);
}

/**
 * Domain-separation constant for the two-key safety number. CLIENT-SIDE
 * visual-verification value only (never sent to or verified by the relay), so
 * it is NOT server-cutover gated. MUST stay byte-identical across
 * Web/iOS/Android (`SafetyNumber.swift`, `SafetyNumber.kt`) or the same key
 * pair produces a different number per platform and verification silently
 * fails. The `-v1` suffix is the migration lever.
 */
export const SAFETY_NUMBER_DOMAIN = "zitrone-safety-number-v1";

/**
 * Safety Number for contact verification: SHA-512 over a domain prefix plus
 * both identity keys, ordered lexicographically so both sides compute the same
 * value. Displayed as the first 30 digest bytes = 60 UPPERCASE hex chars, in
 * 15 groups of 4.
 */
export async function safetyNumber(identityA: Uint8Array, identityB: Uint8Array): Promise<string> {
  await ready();
  const [first, second] =
    compareBytes(identityA, identityB) <= 0 ? [identityA, identityB] : [identityB, identityA];
  const prefix = sodium.from_string(SAFETY_NUMBER_DOMAIN);
  const joined = new Uint8Array(prefix.length + first.length + second.length);
  joined.set(prefix, 0);
  joined.set(first, prefix.length);
  joined.set(second, prefix.length + first.length);
  const digest = sodium.crypto_hash_sha512(joined);
  // 60 hex chars (30 bytes) of entropy shown, uppercase, groups of 4.
  const hex = sodium.to_hex(digest).slice(0, 60).toUpperCase();
  return hex.match(/.{1,4}/g)!.join(" ");
}

function compareBytes(a: Uint8Array, b: Uint8Array): number {
  for (let i = 0; i < Math.min(a.length, b.length); i++) {
    if (a[i]! !== b[i]!) return a[i]! - b[i]!;
  }
  return a.length - b.length;
}
