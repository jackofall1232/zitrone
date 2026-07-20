// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import { aeadDecrypt, aeadEncrypt } from "./aead.js";
import { utf8Decode, utf8Encode } from "./encoding.js";
import { deriveKeyFromPassword, generateSalt } from "./kdf.js";
import {
  fingerprintOf,
  generateIdentityKeyPair,
  generateOneTimePrekeys,
  generateSignedPrekey,
  safetyNumber,
  verifySignedPrekey,
} from "./keys.js";
import { decryptKeyStore, encryptKeyStore, type KeyStore } from "./keystore.js";
import { ratchetDecrypt, ratchetEncrypt } from "./ratchet.js";
import {
  decodeWatermarkPayload,
  embedWatermarkBits,
  embedWatermarkInPixels,
  extractWatermarkBits,
} from "./watermark.js";
import { x3dhInitiate, x3dhRespond } from "./x3dh.js";

async function establishedSessions() {
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

describe("AEAD", () => {
  it("round-trips and produces unique nonces per call", async () => {
    const key = new Uint8Array(32).fill(7);
    const pt = utf8Encode("read it. it's gone.");
    const a = await aeadEncrypt(key, pt);
    const b = await aeadEncrypt(key, pt);
    expect(a).not.toEqual(b); // fresh nonce every call
    expect(utf8Decode(await aeadDecrypt(key, a))).toBe("read it. it's gone.");
  });

  it("rejects tampered ciphertext", async () => {
    const key = new Uint8Array(32).fill(7);
    const box = await aeadEncrypt(key, utf8Encode("hi"));
    box[box.length - 1]! ^= 0xff;
    await expect(aeadDecrypt(key, box)).rejects.toThrow();
  });
});

describe("prekeys", () => {
  it("signed prekey verifies against identity key, fails against another", async () => {
    const id = await generateIdentityKeyPair();
    const other = await generateIdentityKeyPair();
    const spk = await generateSignedPrekey(id, 1);
    expect(await verifySignedPrekey(spk.publicKey, spk.signature, id.publicKey)).toBe(true);
    expect(await verifySignedPrekey(spk.publicKey, spk.signature, other.publicKey)).toBe(false);
  });

  it("safety number is symmetric and formatted in groups of 4", async () => {
    const a = await generateIdentityKeyPair();
    const b = await generateIdentityKeyPair();
    const ab = await safetyNumber(a.publicKey, b.publicKey);
    const ba = await safetyNumber(b.publicKey, a.publicKey);
    expect(ab).toBe(ba);
    expect(ab).toMatch(/^([0-9A-F]{4} ){14}[0-9A-F]{4}$/);
  });

  // Canonical cross-platform vector — pinned identically in the iOS
  // (SafetyNumberTests.swift) and Android (SafetyNumberTest.kt) suites. If any
  // platform's construction drifts (prefix, ordering, truncation, hex case),
  // one of the three suites goes red. Keys are the raw 32-byte published wire
  // form.
  it("safety number matches the canonical cross-platform vector", async () => {
    const keyA = new Uint8Array(Array.from({ length: 32 }, (_, i) => i + 1)); // 0x01..0x20
    const keyB = new Uint8Array(Array.from({ length: 32 }, (_, i) => 255 - i)); // 0xFF..0xE0
    const expected = "005C 0F07 1A4A BF49 3872 21C2 7A0C 8F44 A791 A7A6 DCD2 535C 7815 0963 79A4";
    expect(await safetyNumber(keyA, keyB)).toBe(expected);
    expect(await safetyNumber(keyB, keyA)).toBe(expected);
  });

  it("self-fingerprint is formatted in 15 groups of 4 and differs from a safety number", async () => {
    const a = await generateIdentityKeyPair();
    const b = await generateIdentityKeyPair();
    const fp = await fingerprintOf(a.publicKey);
    expect(fp).toMatch(/^([0-9A-F]{4} ){14}[0-9A-F]{4}$/);
    // Distinct domain constants → a single-key fingerprint can never coincide
    // with a two-key safety number, even were both keys the same.
    expect(fp).not.toBe(await safetyNumber(a.publicKey, b.publicKey));
  });

  // Canonical cross-platform vector — pinned identically in the Android
  // (SafetyNumberTest.kt `single-key fingerprint matches the canonical vector`)
  // and iOS suites. Key is the raw bytes 0x01..0x20.
  it("self-fingerprint matches the canonical cross-platform vector", async () => {
    const key = new Uint8Array(Array.from({ length: 32 }, (_, i) => i + 1)); // 0x01..0x20
    expect(await fingerprintOf(key)).toBe(
      "B7BA C2F0 B9B6 550A 5383 387F 4252 561F BDD2 B4C7 D750 9D3D 7ADC 5AA2 B92E",
    );
  });

  it("self-fingerprint rejects a key that isn't 32 bytes", async () => {
    await expect(fingerprintOf(new Uint8Array(31))).rejects.toThrow(/32 bytes/);
    await expect(fingerprintOf(new Uint8Array(33))).rejects.toThrow(/32 bytes/);
  });
});

describe("X3DH + Double Ratchet", () => {
  it("both sides derive a working session", async () => {
    const { aliceSession, bobSession } = await establishedSessions();
    const msg = await ratchetEncrypt(aliceSession, utf8Encode("hello bob"));
    expect(utf8Decode(await ratchetDecrypt(bobSession, msg))).toBe("hello bob");
  });

  it("ratchets across many turns in both directions", async () => {
    const { aliceSession, bobSession } = await establishedSessions();
    for (let turn = 0; turn < 6; turn++) {
      const [tx, rx] = turn % 2 === 0 ? [aliceSession, bobSession] : [bobSession, aliceSession];
      for (let i = 0; i < 3; i++) {
        const text = `turn ${turn} msg ${i}`;
        const enc = await ratchetEncrypt(tx, utf8Encode(text));
        expect(utf8Decode(await ratchetDecrypt(rx, enc))).toBe(text);
      }
    }
  });

  it("handles out-of-order delivery via skipped message keys", async () => {
    const { aliceSession, bobSession } = await establishedSessions();
    const m1 = await ratchetEncrypt(aliceSession, utf8Encode("first"));
    const m2 = await ratchetEncrypt(aliceSession, utf8Encode("second"));
    const m3 = await ratchetEncrypt(aliceSession, utf8Encode("third"));
    expect(utf8Decode(await ratchetDecrypt(bobSession, m3))).toBe("third");
    expect(utf8Decode(await ratchetDecrypt(bobSession, m1))).toBe("first");
    expect(utf8Decode(await ratchetDecrypt(bobSession, m2))).toBe("second");
  });

  it("rejects a bundle with a forged prekey signature", async () => {
    const alice = await generateIdentityKeyPair();
    const bob = await generateIdentityKeyPair();
    const mallory = await generateIdentityKeyPair();
    const forged = await generateSignedPrekey(mallory, 1); // signed by the wrong identity
    await expect(
      x3dhInitiate(alice, {
        identityKey: bob.publicKey,
        signedPrekey: { id: 1, publicKey: forged.publicKey, signature: forged.signature },
        oneTimePrekey: null,
      }),
    ).rejects.toThrow(/signature/);
  });
});

describe("keystore", () => {
  it("encrypts at rest and fails closed on a wrong passphrase", async () => {
    const salt = await generateSalt();
    const key = await deriveKeyFromPassword("correct horse battery lemon", salt);
    const store: KeyStore = {
      version: 1,
      accountId: "0b9f8c1e-4f2a-4d8b-9c3e-7a6b5d4c3b2a",
      identityKey: { publicKey: "aa", privateKey: "bb" },
      signedPrekeys: [],
      oneTimePrekeys: [],
      sessions: {},
      verifiedContacts: {},
    };
    const blob = await encryptKeyStore(key, store);
    expect(await decryptKeyStore(key, blob)).toEqual(store);

    const wrong = await deriveKeyFromPassword("wrong passphrase", salt);
    await expect(decryptKeyStore(wrong, blob)).rejects.toThrow();
  });
});

describe("watermark", () => {
  it("embeds and extracts a payload from pixel data", () => {
    const pixels = new Uint8ClampedArray(256 * 64 * 4).fill(0x24);
    const payload = utf8Encode(
      JSON.stringify({ userId: "u-1", messageId: "m-1", timestamp: "2026-06-12T00:00:00Z" }),
    );
    embedWatermarkBits(pixels, payload);
    const extracted = extractWatermarkBits(pixels);
    expect(extracted).not.toBeNull();
    expect(decodeWatermarkPayload(extracted!)).toEqual({
      userId: "u-1",
      messageId: "m-1",
      timestamp: "2026-06-12T00:00:00Z",
    });
  });

  it("is visually invisible — pixel deltas are at most 1 LSB", () => {
    const before = new Uint8ClampedArray(64 * 64 * 4).fill(0x42);
    const after = new Uint8ClampedArray(before);
    embedWatermarkBits(after, utf8Encode("leak-attribution"));
    for (let i = 0; i < before.length; i++) {
      expect(Math.abs(after[i]! - before[i]!)).toBeLessThanOrEqual(1);
    }
  });

  it("returns null when no watermark is present", () => {
    expect(extractWatermarkBits(new Uint8ClampedArray(64 * 64 * 4))).toBeNull();
  });

  it("returns null on pixel buffers too small to hold a header", () => {
    expect(extractWatermarkBits(new Uint8ClampedArray(0))).toBeNull();
    expect(extractWatermarkBits(new Uint8ClampedArray(31))).toBeNull();
  });

  it("round-trips a payload embedded over NON-UNIFORM pre-drawn pixels", () => {
    // Simulate a canvas that already carries drawn content (the visible
    // fingerprint tile) rather than a flat fill: a pseudo-random RGBA buffer.
    // The stego layer must still extract cleanly from the composed pixels.
    const width = 512;
    const height = 512;
    const data = new Uint8ClampedArray(width * height * 4);
    for (let i = 0; i < data.length; i++) data[i] = (i * 2654435761) & 0xff;
    const payload = utf8Encode(
      JSON.stringify({
        userId: "acct-9",
        messageId: "chat-list",
        timestamp: "2026-07-20T00:00:00Z",
      }),
    );
    embedWatermarkInPixels(data, width, height, payload);
    const extracted = extractWatermarkBits(data);
    expect(extracted).not.toBeNull();
    expect(decodeWatermarkPayload(extracted!)).toEqual({
      userId: "acct-9",
      messageId: "chat-list",
      timestamp: "2026-07-20T00:00:00Z",
    });
  });

  it("rejects a pixel buffer whose length disagrees with its dimensions", () => {
    expect(() =>
      embedWatermarkInPixels(new Uint8ClampedArray(4 * 10), 3, 3, utf8Encode("x")),
    ).toThrow(/width × height/);
  });
});
