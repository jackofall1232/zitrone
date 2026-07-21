// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// Sub-phase 5b cross-stack proof, the MIRROR of lemondrop-crossfamily.test.ts:
// a lemon drop CREATED by the production Android/Kotlin path (LemonDropCreate —
// a Curve25519/Montgomery sender) must open on the web stack as its true
// recipient. The committed fixtures are the shared bytes:
//
//   - recipient-keys.json          — the Android-family recipient (its private
//     scalars; here the WEB side plays that recipient);
//   - montgomery-sender-fixture.json — the drop the Kotlin creator sealed to it,
//     produced by the real LemonDropCreate (see resources/lemondrop/README.md).
//
// Together with LemonDropCreateTest.kt (create → Android open) this closes the
// Android-creation bridge end to end over one set of bytes, exactly as the
// PR #4 fixtures closed the web-creation → Android-open direction.

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { sodium, ready } from "./sodium.js";
import { fromBase64 } from "./encoding.js";
import { openLemonDrop } from "./lemondrop.js";
import type { IdentityKeyPair, OneTimePrekey, SignedPrekey } from "./keys.js";

const FIXTURE_DIR = resolve(__dirname, "../../../apps/android/app/src/test/resources/lemondrop");

function fixture(name: string): Record<string, string> {
  return JSON.parse(readFileSync(resolve(FIXTURE_DIR, name), "utf8"));
}

describe("a Kotlin-created lemon drop (Montgomery sender) opens on the web stack", () => {
  it("recovers the message, the Montgomery sender key, and the burn token", async () => {
    await ready();
    const keys = fixture("recipient-keys.json");
    const drop = fixture("montgomery-sender-fixture.json");

    // The recipient is Android-family: its identity IS the X25519/Montgomery
    // point already, so the "web" IdentityKeyPair wraps those bytes directly and
    // the Ed25519 slots are unused placeholders (openLemonDrop reads only the
    // x25519 pair for the seal and the DH).
    const identityPub = await fromBase64(keys.identity_pub);
    const identityPriv = await fromBase64(keys.identity_priv);
    const myIdentity: IdentityKeyPair = {
      publicKey: identityPub,
      privateKey: new Uint8Array(64),
      x25519PublicKey: identityPub,
      x25519PrivateKey: identityPriv,
    };
    const mySignedPrekeys: SignedPrekey[] = [
      {
        id: Number(keys.spk_id),
        publicKey: await fromBase64(keys.spk_pub),
        privateKey: await fromBase64(keys.spk_priv),
        signature: await fromBase64(keys.spk_sig),
        createdAt: 1,
      },
    ];
    const myOneTimePrekeys: OneTimePrekey[] = [
      {
        id: Number(keys.otp_id),
        publicKey: await fromBase64(keys.otp_pub),
        privateKey: await fromBase64(keys.otp_priv),
      },
    ];

    const result = await openLemonDrop({
      myIdentity,
      mySignedPrekeys,
      myOneTimePrekeys,
      ciphertext: await fromBase64(drop.ciphertext),
    });

    expect(result.outcome).toBe("message");
    if (result.outcome !== "message") return;
    expect(result.text).toBe(drop.text);
    expect(result.senderAccountId).toBe(drop.sender_account_id);
    // The recovered claimed key is the raw Montgomery sender key verbatim — the
    // exact form a recipient pins/compares as base64, no conversion either side.
    expect(result.senderIdentityKey).toEqual(await fromBase64(drop.sender_identity_pub));
    // The recovered burn token is the preimage of the deposited burn_hash.
    expect(sodium.crypto_hash_sha256(result.burnToken)).toEqual(await fromBase64(drop.burn_hash));
  });
});
