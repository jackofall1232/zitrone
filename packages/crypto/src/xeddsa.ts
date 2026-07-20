// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// ⚠️ This implementation has not undergone third-party security audit.
// See AUDIT.md in the repository root.

/**
 * XEdDSA signature VERIFICATION — the Curve25519-based signing scheme used by
 * libsignal-client's `IdentityKeyPair`/`Curve.calculateSignature`
 * (https://moderncrypto.org/mail-archive/curves/2014/000205.html). Android/iOS
 * identity keys are Curve25519 (required for X3DH's DH agreement) and sign
 * with XEdDSA; web/desktop identity keys are genuine Ed25519 and sign with
 * plain `crypto_sign_detached`. The relay already verifies BOTH conventions
 * (server/internal/auth/xeddsa.go + the try-both callers in handlers.go); this
 * is the client-side port of the same logic, needed so a web/desktop client
 * can verify an Android-family prekey bundle when addressing a lemon drop.
 *
 * This is a deliberate line-for-line behavioral port of the server's
 * `VerifyXEdDSA` (itself ported from go.mau.fi/libsignal's ecc.verify and
 * validated against real libsignal-client signature vectors — the SAME vectors
 * exercised in xeddsa.test.ts here). Any change to one side must be mirrored
 * in the other; divergence would let a bundle the relay served be rejected (or
 * worse, accepted) differently by the client.
 *
 * NOT plain Ed25519: the public key is a Montgomery-form u-coordinate, not an
 * Edwards point, and verification needs the Edwards sign bit XEdDSA smuggles
 * into the otherwise-unused top bit of the signature's S half. The math below
 * runs on PUBLIC inputs only (a published key, a signature, a signed public
 * message), so BigInt's variable-time arithmetic is acceptable — nothing here
 * touches secret key material.
 */

import { sodium, ready } from "./sodium.js";

/** The Curve25519 field prime, 2^255 − 19. */
const P = (1n << 255n) - 19n;

/**
 * libsignal's Curve25519 key-type tag (`Curve.DJB_TYPE`). Mobile clients sign
 * a signed prekey's full libsignal `serialize()` form — this tag byte plus the
 * 32-byte public key — NOT the raw 32-byte wire form the relay stores. The
 * verifying side must reconstruct the same 33-byte message or every valid
 * signature is rejected (server mirror: `signedPrekeyMessage` in handlers.go).
 */
export const DJB_TYPE = 0x05;

/** The exact byte string a mobile client signed for a signed prekey. */
export function signedPrekeyMessage(rawPublicKey: Uint8Array): Uint8Array {
  const out = new Uint8Array(1 + rawPublicKey.length);
  out[0] = DJB_TYPE;
  out.set(rawPublicKey, 1);
  return out;
}

/**
 * Verify an XEdDSA signature under a Curve25519 (Montgomery) public key.
 * Returns false — never throws — on any malformed input, mirroring the
 * server's fail-closed shape.
 */
export async function verifyXEdDSA(
  curve25519PublicKey: Uint8Array,
  message: Uint8Array,
  signature: Uint8Array,
): Promise<boolean> {
  await ready();
  if (curve25519PublicKey.length !== 32 || signature.length !== 64) return false;

  // The top bit of a Curve25519 u-coordinate isn't part of the field element
  // (RFC 7748 §5); clear it before treating the bytes as a field value,
  // matching how libsignal encodes/decodes Montgomery keys. Values in
  // [p, 2^255) then reduce mod p, matching the server's field.Element.
  const pubBytes = curve25519PublicKey.slice();
  pubBytes[31]! &= 0x7f;
  const montX = fromLittleEndian(pubBytes) % P;

  // mont_x = -1 (mod p) makes the birational map below undefined (mont_x+1 has
  // no inverse); reject the degenerate key explicitly rather than letting a
  // meaningless ed_y flow into signature verification (server mirror: the
  // montXPlusOne zero check added on PR #22 review).
  if (montX === P - 1n) return false;

  // Convert the Montgomery-form public key into the corresponding Edwards-form
  // Ed25519 public key: ed_y = (mont_x − 1) / (mont_x + 1).
  const edY = (((montX - 1n + P) % P) * modInverse((montX + 1n) % P)) % P;

  // y alone doesn't determine the sign of the Edwards x-coordinate — XEdDSA
  // signing stores it in the otherwise-unused top bit of the signature's S
  // (Ed25519 S values are always < the group order, well under 2^255). Move it
  // into the reconstructed public key, then mask it back out of S before
  // handing off to standard Ed25519 verification.
  const edPublicKey = toLittleEndian(edY);
  edPublicKey[31]! |= signature[63]! & 0x80;
  const sig = signature.slice();
  sig[63]! &= 0x7f;

  // libsodium's verifier is STRICTER than the server's Go crypto/ed25519 (it
  // additionally rejects small-order and non-canonical A) — strictness that
  // only ever fails closed, never accepts what the server would reject.
  try {
    return sodium.crypto_sign_verify_detached(sig, message, edPublicKey);
  } catch {
    return false;
  }
}

/** Inverse mod P via Fermat: a^(p−2). Callers guarantee a ≠ 0. */
function modInverse(a: bigint): bigint {
  let result = 1n;
  let base = a % P;
  let exp = P - 2n;
  while (exp > 0n) {
    if (exp & 1n) result = (result * base) % P;
    base = (base * base) % P;
    exp >>= 1n;
  }
  return result;
}

function fromLittleEndian(bytes: Uint8Array): bigint {
  let value = 0n;
  for (let i = bytes.length - 1; i >= 0; i--) {
    value = (value << 8n) | BigInt(bytes[i]!);
  }
  return value;
}

function toLittleEndian(value: bigint): Uint8Array {
  const out = new Uint8Array(32);
  let v = value;
  for (let i = 0; i < 32; i++) {
    out[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  return out;
}
