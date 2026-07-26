# Zitrone — Project Blueprint

## Mission
Zitrone is a zero-knowledge, plausible-deniability encrypted messenger. The server relays and
stores only opaque ciphertext (envelopes, blobs, dead-drops) and can prove nothing about who
talks to whom or what was said; deletion of a ciphertext row *is* the shred. The Android client
is the security reference implementation and is where the plausible-deniability vault
(multi-vault, passphrase/biometric unlock, no-remanence account delete) lives.

Primary users: people who need messaging that leaks nothing to a compromised or subpoenaed
server, and that can be unlocked to a decoy state under coercion.

Success: every platform is honest about exactly what it can and cannot guarantee; the server
never holds a key or a linkage; and durable client-side security state (delete markers, auth
tokens, vault seal) is provably correct under crash, concurrency, and coercion.

## Architecture
pnpm monorepo (`/root/zitrone`). Runtime boundaries:

| Component | Stack | Role |
|-----------|-------|------|
| **Relay server** | Go / Fiber + PostgreSQL | Zero-knowledge store-and-forward. Envelopes, blobs, dead-drops; janitor purges expired rows (delete-row = shred). Holds **no** AEAD keys, no plaintext, no social graph. |
| **Android** | Kotlin / Jetpack Compose | **Security reference client.** Plausible-deniability vault (`crypto/vault/`), session-over-vault, WebSocket transport (no push stack), account-delete state machine. |
| **iOS** | SwiftUI | Client; trails the reference (see honesty hierarchy). Not locally buildable on CX33 — manual Xcode verify. |
| **Web** | React / Vite | Client; runs in-browser. Compose, lemon-drop create, watermark. |
| **Linux desktop** | Tauri / Rust shell over the web client | Desktop client. |

Key Android internals (the hardened surface): `crypto/vault/VaultSession` + `VaultRuntime`
(seal/reseal/wipe), `UnlockController` (session lifecycle, `lock()` teardown, `terminalWipe`
flag), `MessagingCoordinator` (WS transport + delivery), the two-marker account-delete state
machine (`vault.delete-intent` vs `vault.delete-confirmed`), and D3's `VaultLockManager`
(idle auto-lock over ProcessLifecycleOwner).

## Requirements
- [x] Server stays zero-knowledge: no keys, no plaintext, no linkage; deletion is shred.
- [x] Android plausible-deniability vault: onboarding passphrase + biometric unlock,
      session-over-vault, flush-before-ack durability, atomic contact delete, no-remanence
      account delete (0.9.1 vault track, PR-D).
- [x] Account-delete correctness: two-marker state machine; a plain lock never clears tokens
      or writes delete markers (16-round-hardened — see `failures.md`).
- [x] D3 idle auto-lock: device-level configurable timeout, honest no-push tradeoff copy.
- [ ] D5 (scope to be re-derived) + PR-F docs → 0.9.1-beta release gate.
- [ ] Standing hygiene before external testers: fix broken CI SAST + release-apk.yml
      shell-injection; storage-format-stability decision; website web-overclaim.

## Definition of Done
Per-release, gated. For 0.9.1-beta: PR-D (D2c✅ + D3 review→merge) + D5 + PR-F all merged with
independent review clean and CI green; version bumped only on explicit human approval; signed
APK verified against cert `6c7f92a7…892753`; docs disclose fresh-install-required and the
storage-format-stability decision; then GH release + Vercel apex flip.

## Non-Execution Boundary
This blueprint is guidance for implementation loops. This `.l00prite/` was scaffolded additively
into a live, mature repo — it is **memory**, not a fresh project. Execution Mode ships disarmed
(`heartbeat.json` `execution.enabled: false`). No agent runs execute-loop, bumps a version, or
pushes/merges without explicit human approval.
