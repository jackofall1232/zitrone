// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// Cross-family lemon-drop creation: the web stack addressing a REAL
// Android-family recipient (libsignal-generated keys with a genuine XEdDSA
// signed-prekey signature — the committed fixture that the Android JVM test
// decrypts; see apps/android .../resources/lemondrop/README.md). The decrypt
// half of the round trip lives in LemonDropOneShotTest.kt on the Android
// side; together the two prove the bridge end to end over the same bytes.

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { fromBase64 } from "./encoding.js";
import { generateIdentityKeyPair } from "./keys.js";
import { createLemonDrop } from "./lemondrop.js";
import { x3dhInitiate, type DecodedPreKeyBundle } from "./x3dh.js";

const FIXTURE_DIR = resolve(
  __dirname,
  "../../../apps/android/app/src/test/resources/lemondrop",
);

async function androidFamilyBundle(): Promise<DecodedPreKeyBundle> {
  const keys = JSON.parse(readFileSync(resolve(FIXTURE_DIR, "recipient-keys.json"), "utf8"));
  return {
    identityKey: await fromBase64(keys.identity_pub),
    signedPrekey: {
      id: keys.spk_id,
      publicKey: await fromBase64(keys.spk_pub),
      signature: await fromBase64(keys.spk_sig),
    },
    oneTimePrekey: { id: keys.otp_id, publicKey: await fromBase64(keys.otp_pub) },
  };
}

describe("lemon drops to an Android-family recipient", () => {
  it("refuses a mobile bundle for ordinary messaging (allowCrossFamily default false)", async () => {
    // The regression guard: addContact/sendMessage go through x3dhInitiate with
    // the default, and a web↔mobile ordinary session would send undecryptable
    // messages — so a mobile bundle must be refused here, not silently accepted.
    const sender = await generateIdentityKeyPair();
    const bundle = await androidFamilyBundle();
    await expect(x3dhInitiate(sender, bundle)).rejects.toThrow(
      "cross-family bundle not supported for ordinary messaging",
    );
  });

  it("verifies the XEdDSA bundle and creates a drop (curve25519 family)", async () => {
    const sender = await generateIdentityKeyPair();
    const bundle = await androidFamilyBundle();

    const init = await x3dhInitiate(sender, bundle, { allowCrossFamily: true });
    expect(init.identityKeyFamily).toBe("curve25519");

    const drop = await createLemonDrop({
      senderAccountId: "aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb",
      senderIdentity: sender,
      recipientAccountId: "cccccccc-4444-4555-9666-dddddddddddd",
      recipientBundle: bundle,
      text: "hello, other family",
    });
    // Sealed and padded like any drop: 256-byte blocks, nothing family-shaped
    // on the wire.
    expect(drop.ciphertext.length % 256).toBe(0);
    expect(drop.qrId).toHaveLength(16);
  });

  it("fails closed on a tampered Android-family bundle signature", async () => {
    const sender = await generateIdentityKeyPair();
    const bundle = await androidFamilyBundle();
    bundle.signedPrekey.signature[5]! ^= 0x01;
    await expect(
      createLemonDrop({
        senderAccountId: "aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb",
        senderIdentity: sender,
        recipientAccountId: "cccccccc-4444-4555-9666-dddddddddddd",
        recipientBundle: bundle,
        text: "must not be created",
      }),
    ).rejects.toThrow("prekey bundle signature verification failed");
  });
});
