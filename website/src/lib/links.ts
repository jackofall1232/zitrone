// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

export const GITHUB_URL = "https://github.com/jackofall1232/zitrone";
export const SECURITY_MODEL_DOC = `${GITHUB_URL}/blob/main/docs/SECURITY_MODEL.md`;
export const SELF_HOSTING_DOC = `${GITHUB_URL}/blob/main/docs/SELF_HOSTING.md`;
export const SECURITY_POLICY = `${GITHUB_URL}/blob/main/SECURITY.md`;
export const AUDIT_LOG = `${GITHUB_URL}/blob/main/AUDIT.md`;
export const GITHUB_ISSUES = `${GITHUB_URL}/issues`;

// ── Android beta (temporary) ─────────────────────────────────────────────────
// Sideloaded beta APK hosted as a GitHub Release asset. After uploading the
// build: set ANDROID_BETA_VERSION to the release tag, name the asset to match
// ANDROID_BETA_APK_URL, and paste the asset's SHA-256 into ANDROID_BETA_SHA256
// (`sha256sum <file>`). Remove this block and the /download/beta page once the
// app ships to the Play Store.
// Release pointer — updated every Android release (version tag + asset checksum).
// Both surfaces (this GitHub Release asset and the Tor onion mirror) must serve a
// byte-identical binary with a matching checksum.
export const ANDROID_BETA_VERSION = "v0.8.4-beta";
export const ANDROID_BETA_APK_URL = `${GITHUB_URL}/releases/download/${ANDROID_BETA_VERSION}/zitrone-${ANDROID_BETA_VERSION}.apk`;
// 64 hex chars. Must be byte-identical to onion-site/SHA256SUMS — both surfaces
// must serve the same binary. Verify: sha256sum onion-site/zitrone-v0.8.4-beta.apk
export const ANDROID_BETA_SHA256 =
  "07588b4d77d8a13c843cd786aa7227a542ffc67f89e1a63ff8e68a2c1d52bd20";
export const ANDROID_BETA_MIN_OS = "Android 8.0 (Oreo)";

// Single source of truth for "is there actually a downloadable release?".
// Every download CTA gates on this so the site never links to a release asset
// that doesn't exist yet (a 404 is a bad look AND dishonest). It flips to true
// automatically the moment ANDROID_BETA_SHA256 is filled with the real 64-char
// checksum on the first Zitrone release — no other code change needed to go
// live. (A populated checksum is the tell: it only exists once a build is cut.)
export const ANDROID_BETA_PUBLISHED = /^[0-9a-f]{64}$/i.test(ANDROID_BETA_SHA256);

// ── Tor download mirror ───────────────────────────────────────────────────────
// Public .onion address for the APK download mirror (served by the Tor hidden
// service overlay). This is the publicly-published address — never the relay or
// secret mirror addresses, which are never rendered into any public page.
export const PUBLIC_MIRROR_ONION = "wyymleg2e3mdhib4twyu7bgofyxbtoj52jfycc4ihqc7atapxyj3kuqd.onion";
