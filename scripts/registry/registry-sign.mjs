#!/usr/bin/env node
// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// SPDX-License-Identifier: AGPL-3.0-only
//
// Registry manifest signing tool — docs/design/REGISTRY_RESOLUTION.md §1–§2.
//
// Zero dependencies on purpose: Node's built-in crypto speaks RFC 8032 Ed25519,
// the same algorithm libsodium's crypto_sign_detached implements, so signatures
// made here verify byte-for-byte under lazysodium on Android. Runs offline —
// keygen and sign are meant for the maintainer's OFFLINE machine (the secret
// key never touches the repo, CI, or any server box).
//
//   registry-sign.mjs keygen  <keyId> <outDir>
//   registry-sign.mjs sign    <manifest.json> <secret.pem> <keyId> [out.json]
//   registry-sign.mjs verify  <envelope.json> <pubkey-base64url> [prevEnvelope.json]
//
// keygen writes <keyId>.secret.pem (mode 600, PKCS8) and <keyId>.pub.txt (the
// raw 32-byte public key, base64url — the exact string REGISTRY_PUBKEY_ED25519
// takes at APK build time).
//
// sign emits the envelope: the manifest file's EXACT BYTES base64url-encoded as
// `payload`, plus a detached signature over those same bytes. Nothing is ever
// re-serialized, so there is no canonicalization to get wrong — what you sign
// is what every client verifies.
//
// verify re-runs exactly the client's checks (signature, schema, validity
// window, relay shape) and, given the previous envelope, the previousManifestHash
// chain — exit 0 only when a client built with this pubkey would accept it.

import { createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify, createHash } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { join } from "node:path";

const b64url = (buf) => Buffer.from(buf).toString("base64url");
const fromB64url = (s) => Buffer.from(s, "base64url");

// Raw 32-byte Ed25519 public key from a node KeyObject (last 32 bytes of SPKI DER).
const rawPub = (keyObj) => keyObj.export({ type: "spki", format: "der" }).subarray(-32);

function keygen(keyId, outDir) {
  mkdirSync(outDir, { recursive: true });
  const secretPath = join(outDir, `${keyId}.secret.pem`);
  const pubPath = join(outDir, `${keyId}.pub.txt`);
  // NEVER overwrite: replacing a registry key strands every APK built with the old
  // pubkey, and writeFileSync's mode applies only at creation — an overwrite would
  // also keep whatever permissions the old file had. flag "wx" = exclusive create.
  for (const p of [secretPath, pubPath])
    if (existsSync(p)) throw new Error(`refusing to overwrite existing ${p} — pick a new keyId or move the old keypair first`);
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  writeFileSync(secretPath, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600, flag: "wx" });
  const pubB64 = b64url(rawPub(publicKey));
  writeFileSync(pubPath, pubB64 + "\n", { flag: "wx" });
  console.log(`secret : ${secretPath} (mode 600 — offline custody, never committed)`);
  console.log(`pubkey : ${pubB64}`);
  console.log(`         ^ this is the REGISTRY_PUBKEY_ED25519 build-time value`);
}

function signManifest(manifestPath, secretPemPath, keyId, outPath) {
  const payloadBytes = readFileSync(manifestPath); // exact bytes — never re-serialized
  validatePayload(JSON.parse(payloadBytes.toString("utf8"))); // refuse to sign garbage
  const key = createPrivateKey(readFileSync(secretPemPath));
  const signature = sign(null, payloadBytes, key); // null digest = pure Ed25519
  const envelope = {
    envelopeVersion: 1,
    payload: b64url(payloadBytes),
    signatures: [{ keyId, algorithm: "ed25519", signature: b64url(signature) }],
  };
  const out = JSON.stringify(envelope, null, 1) + "\n";
  if (outPath) writeFileSync(outPath, out);
  else process.stdout.write(out);
  console.error(`signed ${manifestPath} (${payloadBytes.length} payload bytes) with ${keyId}`);
  console.error(`envelope sha256 (base64url): ${b64url(createHash("sha256").update(out).digest())}`);
  console.error(`  ^ the NEXT manifest's previousManifestHash`);
}

function validatePayload(m) {
  const fail = (msg) => { throw new Error(`manifest invalid: ${msg}`); };
  if (m.schemaVersion !== 1) fail(`schemaVersion must be 1, got ${m.schemaVersion}`);
  if (!Number.isInteger(m.epoch) || m.epoch < 1) fail("epoch must be a positive integer");
  const from = Date.parse(m.validFrom); const until = Date.parse(m.validUntil);
  if (Number.isNaN(from) || Number.isNaN(until)) fail("validFrom/validUntil must be ISO 8601");
  if (until <= from) fail("validUntil must be after validFrom");
  if (m.epoch === 1 ? m.previousManifestHash !== null : typeof m.previousManifestHash !== "string")
    fail("previousManifestHash: null only at epoch 1, required string after");
  if (!Array.isArray(m.relays) || m.relays.length === 0) fail("relays must be non-empty");
  for (const r of m.relays) {
    if (typeof r.id !== "string" || !r.id) fail("every relay needs an id");
    if (!r.clearnet && !r.onion && !r.i2p) fail(`relay ${r.id} has no endpoint at all`);
    if (r.clearnet && !(r.clearnet.apiBaseUrl?.startsWith("https://") && r.clearnet.wsUrl?.startsWith("wss://")))
      fail(`relay ${r.id} clearnet endpoints must be https/wss`);
    // The client reads EXACTLY these shapes (ManifestVerifier: onion.address / i2p.dest) and
    // silently treats anything else as endpoint-absent — refuse to sign what it cannot read.
    if (r.onion != null && !(typeof r.onion === "object" && typeof r.onion.address === "string" && r.onion.address))
      fail(`relay ${r.id} onion must be null or {"address": "<non-empty>"}`);
    if (r.i2p != null && !(typeof r.i2p === "object" && typeof r.i2p.dest === "string" && r.i2p.dest))
      fail(`relay ${r.id} i2p must be null or {"dest": "<non-empty>"}`);
  }
}

function verifyEnvelope(envelopePath, pubkeyB64url, prevEnvelopePath) {
  const envBytes = readFileSync(envelopePath);
  const env = JSON.parse(envBytes.toString("utf8"));
  if (env.envelopeVersion !== 1) throw new Error(`unknown envelopeVersion ${env.envelopeVersion}`);
  const payloadBytes = fromB64url(env.payload);
  const raw = fromB64url(pubkeyB64url);
  if (raw.length !== 32) throw new Error("pubkey must be 32 raw bytes, base64url");
  // Rebuild an SPKI KeyObject around the raw key (fixed 12-byte Ed25519 prefix).
  const spki = Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), raw]);
  const keyObj = createPublicKey({ key: spki, format: "der", type: "spki" });
  const ok = env.signatures.some(
    (s) => s.algorithm === "ed25519" && verify(null, payloadBytes, keyObj, fromB64url(s.signature)),
  );
  if (!ok) throw new Error("NO valid signature for this pubkey");
  const m = JSON.parse(payloadBytes.toString("utf8"));
  validatePayload(m);
  const now = Date.now();
  // Mirror the client exactly: ManifestVerifier tolerates 24h of validFrom skew and
  // no more — a manifest further ahead passes nothing, so "OK" here would be a lie.
  const VALID_FROM_SKEW_MS = 24 * 60 * 60 * 1000;
  if (now < Date.parse(m.validFrom) - VALID_FROM_SKEW_MS)
    throw new Error(`NOT YET VALID: validFrom ${m.validFrom} is more than 24h ahead — every client rejects this today`);
  if (now < Date.parse(m.validFrom)) console.error(`WARN: not yet valid (validFrom ${m.validFrom}) — inside the 24h client skew window`);
  if (now > Date.parse(m.validUntil)) throw new Error(`EXPIRED at ${m.validUntil}`);
  if (prevEnvelopePath) {
    const prevBytes = readFileSync(prevEnvelopePath);
    const prevHash = b64url(createHash("sha256").update(prevBytes).digest());
    if (m.previousManifestHash !== prevHash)
      throw new Error(`previousManifestHash mismatch: manifest says ${m.previousManifestHash}, previous file hashes to ${prevHash}`);
    // Epochs are strictly increasing along the chain. A reused or lower epoch either
    // strands updated clients (their high-water floor refuses it) or leaves two
    // different relay sets the floor cannot order — refuse to bless either.
    const prevEpoch = JSON.parse(fromB64url(JSON.parse(prevBytes.toString("utf8")).payload).toString("utf8")).epoch;
    if (!(m.epoch > prevEpoch))
      throw new Error(`epoch must increase along the chain: previous envelope is epoch ${prevEpoch}, manifest says ${m.epoch}`);
  }
  console.log(`OK: epoch ${m.epoch}, ${m.relays.length} relay(s), valid until ${m.validUntil}`);
  console.log(`    signed by: ${env.signatures.map((s) => s.keyId).join(", ")}`);
}

const [, , cmd, ...args] = process.argv;
try {
  if (cmd === "keygen" && args.length === 2) keygen(args[0], args[1]);
  else if (cmd === "sign" && (args.length === 3 || args.length === 4)) signManifest(...args);
  else if (cmd === "verify" && (args.length === 2 || args.length === 3)) verifyEnvelope(...args);
  else {
    console.error("usage: registry-sign.mjs keygen <keyId> <outDir>");
    console.error("       registry-sign.mjs sign <manifest.json> <secret.pem> <keyId> [out.json]");
    console.error("       registry-sign.mjs verify <envelope.json> <pubkey-base64url> [prevEnvelope.json]");
    process.exit(1);
  }
} catch (e) {
  console.error(`FAIL: ${e.message}`);
  process.exit(1);
}
