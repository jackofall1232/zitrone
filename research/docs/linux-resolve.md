# libsignal Cross-Platform Interop

**Linux/Tauri (native Rust) ↔ Android/iOS (Swift/Kotlin bindings)**

Reconciled research handoff — merges three independent research passes into one authoritative brief.

*Zitrone Crypto Unification · Desktop path · Compiled 2026-07-21 · Standalone research; no code lands in the Zitrone repo.*

---

> **How to read this document**
>
> This reconciles three separate research passes (referred to as **A**, **B**, and **C**). Where all three agree, treat it as high-confidence. Where they conflict, the conflict is called out explicitly and marked **VERIFY AT BUILD TIME** — do not pick one on faith.
>
> **The one rule that overrides everything:** any version number, tag, or exact function signature in here must be re-checked against the official repo at the moment you build. These drift weekly and the three passes already disagree on them.

---

## 1. Verdict

> **All three passes independently reached the same conclusion**
>
> **YES** — real two-way interop between a Linux/Tauri native-Rust `libsignal-protocol` client and Android/iOS `libsignal-client` clients is achievable today, **conditional** on the five requirements in §2.

The reason interop is expected to work at all: the Rust core, the Swift binding, and the Kotlin/Java binding are all built from the *same* `signalapp/libsignal` monorepo tag. The mobile bindings are thin front-ends over the identical Rust implementation the Linux client would use. So the Rust↔mobile boundary itself is **not** the likely failure point. The real traps are the conditions below.

---

## 2. The five conditions that must all hold

*Every pass converged on these. If any one fails, interop fails even though "both sides use libsignal."*

| # | Condition | What it means in practice |
|---|-----------|---------------------------|
| 1 | **PQXDH / Kyber parity** | The single biggest interop breaker (all three rank it #1). Current mobile clients require a signed Kyber (ML-KEM / Kyber1024) prekey in the bundle. A classic-X3DH-only handshake is **rejected** for new sessions. The Linux side must generate, publish, and consume Kyber prekeys. |
| 2 | **Matched version window** | Same monorepo tag on both sides is the safe configuration. A mismatched pair must be **tested**, never assumed. "Both 0.x" or "one minor apart" is not evidence of compatibility. |
| 3 | **Exact ProtocolAddress parity** | Both sides must reproduce the identical `(name, device_id)` tuple. Any difference in casing, UUID formatting, prefixes, or device ID produces a different address and breaks decrypt. |
| 4 | **Library-owned serialization** | Let libsignal serialize/deserialize messages and records. Preserve exact ciphertext bytes end-to-end and carry the ciphertext-type discriminator *separately*. Do not hand-roll a wire format or infer message type from raw bytes. |
| 5 | **Correct store semantics** | Identity-trust behavior, registration IDs, one-time EC prekey deletion, and Kyber prekey reuse tracking must be implemented correctly. The reference in-memory store is fine for a spike but **not** production-complete (see §5). |

---

## 3. Conflicts between the three passes — resolve at build time

The passes disagree on specifics that would sink a spike if trusted blindly. This is the same class of error that already bit this project once (a prior handoff pinned a version tag that did not exist). **Treat every row below as an action item, not a fact.**

| Point of conflict | What each pass claimed | Resolution |
|-------------------|------------------------|------------|
| **Latest release tag** | **A:** `v0.97.4` · **B:** `v0.97.2` · **C:** `v0.97.5`. They cannot all be right. | **VERIFY AT BUILD TIME.** Check the releases page; pin whatever actually exists. |
| **Mobile-pinned version** | **A:** Signal-Android/iOS pin `0.97.3`. **B/C:** not independently established. | Confirm the tag the mobile apps actually ship, then match or test against it. |
| **Ratchet internals** | **A:** current libsignal delegates to "TripleRatchet" / SPQR, no longer plain Double Ratchet. **B/C:** silent on this. | Treat as A-only / unconfirmed. Safe posture regardless: let libsignal negotiate the ratchet; never recreate a wire format. |
| **Exact fn signatures** | **A:** gives full signatures with RNG + `SystemTime`. **B:** inferred RNG, ms-epoch timestamps. **C:** could not extract, flagged "needs local source." | Use A's signatures as a starting shape; confirm against `rust/protocol/tests/` at your pinned tag. |
| **Store trait Send-ness** | **A:** traits are `#[async_trait(?Send)]` (futures not Send). **B:** says stores must be `Send + Sync` for Tauri state. | Both can be true (the *handle* in Tauri state is Send+Sync; the store futures are not). Use the worker pattern in §7. |

---

## 4. PQXDH / Kyber — the #1 risk, in detail

Because this is the most likely thing to break, here is what a **current mobile-compatible prekey bundle must contain**. A Linux client publishing only identity + signed Curve25519 prekey + optional one-time EC prekey **will not interoperate** for new sessions.

| Bundle component | Status | Notes |
|------------------|--------|-------|
| Identity key | **Required** | — |
| Registration ID | **Required** | — |
| Device ID | **Required** | 1–127 |
| Signed EC (Curve25519) prekey + signature | **Required** | — |
| One-time EC prekey | *Optional* | Bundle accepts none |
| Signed Kyber prekey + signature | **Required** | One-time OR last-resort |

A Kyber prekey may be a one-time PQ prekey or a last-resort PQ prekey, but **one of them must be present**, matching the official PQXDH spec. Inbound processing on current versions explicitly rejects the pre-Kyber message version and rejects a missing PQ prekey ID or missing PQ ciphertext. (Narrow exception: an already-established matching session can continue; this does not revive classic X3DH for new sessions.)

---

## 5. Stores: use the reference store for the spike, not for production

All three passes confirm the crate ships a reference in-memory store (`InMemSignalProtocolStore`, composed of in-memory Session / PreKey / SignedPreKey / KyberPreKey / Identity / SenderKey stores). **This is sufficient to prove interop — you do not need to hand-roll a store just to run the spike.**

> ⚠️ **Production caveat (do not skip)**
>
> The reference Kyber store does **not** delete keys automatically after use — acceptable for a last-resort key, wrong for a one-time-key policy. A production store must enforce one-time EC prekey deletion, call `mark_kyber_pre_key_used`, prevent one-time Kyber reuse, and reject a repeated signed-prekey/base-key combination. Signal also states external use is unsupported and APIs may change without notice — **you own tracking upstream.**

---

## 6. Addressing & scope — what Zitrone must match vs. can replace

Protocol-level compatibility is not the same as Signal-service compatibility. Zitrone keeps its own transport and single-device identity model. Split:

**Must match (protocol-level)**

- Identity key formats and identity-trust behavior
- Registration IDs (14-bit range — 1 to 16,380 — is the conservative mobile-compatible choice)
- Exact local and remote `ProtocolAddress` values; `device_id = 1` for a single-device model
- Signed EC prekeys, mandatory signed Kyber prekeys, prekey IDs, message type, exact ciphertext bytes, ratchet/session-record semantics

**Can replace (Signal-service-level)**

- Phone-number registration, ACI/PNI account discovery, Signal's prekey server, multi-device fan-out, sealed-sender certificates, key-transparency, groups — none required for raw two-way session interop

> **Decide this explicitly**
>
> Pass B raises the one real fork: if you ever target the *actual Signal network*, the mobile apps wrap ciphertext in outer transport envelopes (Envelope/Content/DataMessage protobufs) you would have to replicate exactly. For a **Zitrone-only transport this is moot** — you define your own bundle and message framing. Confirm which world you are in before building.

---

## 7. Tauri integration: the async / non-Send constraint

Pass A's most actionable Tauri-specific finding: the store traits use `#[async_trait(?Send)]`, so their futures are not guaranteed `Send`, while Tauri async commands run as spawned tasks that generally want `Send` futures and `Send + Sync` managed state. These can conflict at compile time.

**Recommended architecture (avoids relying on uncertain macro/runtime behavior):**

- Run a dedicated **protocol worker** on one OS thread with a current-thread Tokio runtime (or `LocalSet`), owning all five stores.
- Tauri commands send *owned* request data over a channel and await an owned response. Only the channel handle lives in Tauri managed state — that handle is `Send + Sync`; the non-Send store futures never cross the command boundary.
- This also serializes all session mutation, preventing two commands advancing the same ratchet concurrently.
- Prefer owned command args (`String`, `Vec<u8>`) over borrowed `&str`/`&[u8]` in async commands.

---

## 8. Linux build reality

A native (non-wasm) build of the *protocol crate only* has no BoringSSL or networking dependency — that blocker applies to the full client, not the piece you need. Toolchain (all three broadly agree):

- System packages: `clang libclang-dev cmake make protobuf-compiler libprotobuf-dev python3 git` (`pkg-config` may be pulled in downstream; not in Signal's baseline list)
- Pin the Rust toolchain to whatever the pinned tag's CI uses (A observed a pinned nightly and edition 2024 / MSRV ~1.85 at its inspected tag — confirm at your tag).
- `x86_64-unknown-linux-gnu`: strongly supported. `aarch64-unknown-linux-gnu`: feasible but needs a real arm64 build to confirm (arm64 artifacts are marked experimental upstream).
- Consume the crate as a **git dependency pinned by tag/rev** — the internal crate version is `0.1.0` and is meaningless as a release identifier; never pin by that number.

---

## 9. The spike: concrete interop test plan

*Adapted from Pass A's test plan (the most complete of the three). Goal: prove PQXDH establishment + bidirectional ratchet, both directions, across the Rust↔binding boundary. Nothing lands in the Zitrone repo — throwaway repo only.*

### 9.1 Harness order

Start with **Rust ↔ Node** (`@signalapp/libsignal-client` has Linux binaries and current tests — fastest way to isolate the Rust-vs-binding boundary). Then run the same vectors through **Kotlin/JVM** and **Swift** (on macOS). Node is a fast first proxy, not a substitute for the real mobile binding runs.

### 9.2 Version matrix

| Initiator | Receiver | Purpose |
|-----------|----------|---------|
| Rust (pinned tag) | Node (same tag) | Same-tag control |
| Node (same tag) | Rust (pinned tag) | Reverse same-tag control |
| Rust (your tag) | Node (mobile's tag) | The real cross-version pair |
| Rust (your tag) | Kotlin/JVM (mobile's tag) | Actual Android binding |
| Kotlin/JVM | Rust | Reverse Android |
| Rust | Swift (mobile's tag) | Actual iOS binding |
| Swift | Rust | Reverse iOS |

### 9.3 Per-peer setup

For each of Alice and Bob, generate and persist: protocol address name; `device_id = 1`; registration ID (14-bit range); identity key pair; signed EC prekey + identity signature; Kyber1024 key pair + identity signature over its serialized public key; optional one-time EC prekey. The receiver must save all private records before publishing its bundle. Use `InMemSignalProtocolStore` for the Rust side.

### 9.4 Bundle transport

`PreKeyBundle` is not a ciphertext type — it is assembled from fields. Exchange a neutral JSON/CBOR object (reg ID, device ID, identity public key, signed-prekey id/public/signature, kyber-prekey id/public/signature, optional one-time prekey id/public). Base64 only as harness transport; decode to exact bytes before handing to libsignal. Each peer reconstructs its own native `PreKeyBundle`.

### 9.5 Direction A — Rust initiates (then reverse for Direction B)

1. Binding publishes Bob's complete PQXDH bundle.
2. Rust reconstructs `PreKeyBundle` and calls `process_prekey_bundle`.
3. Rust encrypts message 0; assert output type is `PreKeySignalMessage`.
4. Serialize exact bytes → binding reconstructs and decrypts with all stores → assert exact plaintext.
5. Binding encrypts a reply; assert type is ordinary `SignalMessage`; Rust decrypts → assert exact plaintext.
6. Then run Direction B: Rust publishes the receiver bundle and the binding initiates (tests Rust-generated Kyber bundle consumption and binding-generated Kyber ciphertext consumption by Rust).

### 9.6 Ratchet + lifecycle + negative controls

- **Ratchet:** ≥25 messages each direction, alternating traffic, shuffled/out-of-order delivery, delayed early message, replay of an accepted ciphertext. Assert every plaintext.
- **Lifecycle:** one-time EC prekey removed when used; `mark_kyber_pre_key_used` called; one-time Kyber not reusable; last-resort key's signed-prekey/base-key combo not accepted twice.
- **Negative controls (must fail as expected):** strip Kyber prekey; strip Kyber ciphertext; corrupt EC signature; corrupt Kyber signature; wrong remote address; wrong device ID; wrong ciphertext-type discriminator; swapped identity key post-session; classic pre-Kyber X3DH attempt.

### 9.7 Pass definition

> **Interop passes only when ALL hold**
>
> - Both sides can initiate; both decrypt the initial prekey message; both decrypt ordinary follow-ups.
> - ≥25 messages each direction succeed; shuffled delivery works within skipped-key limits; plaintext matches exactly.
> - Correct prekeys consumed / marked used; address + registration metadata consistent.
> - The exact version pair you intend to ship passes — and the same vectors pass through BOTH Kotlin and Swift bindings.

---

## 10. Consolidated risk & assumption ledger

| Assumption / risk | Status | Note |
|-------------------|--------|------|
| Mobile handshakes require PQXDH; Kyber prekey mandatory | **Confirmed (all 3)** | Highest-priority condition |
| One-time EC prekey optional | **Confirmed (A)** | Bundle accepts none |
| Rust core & mobile bindings share one implementation | **Confirmed (all 3)** | Why interop is expected |
| Reference in-memory store exists & suffices for spike | **Confirmed (all 3)** | Not production-complete |
| ProtocolAddress = (name, device_id); exact match required | **Confirmed (all 3)** | Casing/format sensitive |
| Native protocol-crate build needs no BoringSSL | **Confirmed (all 3)** | x64 solid; arm64 needs build |
| Latest release tag / mobile-pinned tag | **CONFLICT** | A/B/C disagree — verify |
| Exact function signatures (RNG, timestamp) | **Partial** | A detailed; confirm at tag |
| Ratchet delegates to TripleRatchet/SPQR | **A-only** | Don't recreate wire format regardless |
| Specific X↔Y version pair interoperates | **Needs build** | Test the exact pair |
| Store futures awaitable in any Tauri command | **Needs build** | Use worker pattern (§7) |
| aarch64 Linux native build | **Needs build** | Experimental upstream |
| External use is a stable supported API | **Refuted** | You own upstream tracking |

---

## 11. Bottom line for the dev picking this up

The architecture is sound and the requirements are well-understood across three independent passes. **Nothing has been compiled or round-tripped yet** — all three stopped at "needs an executable spike." That spike (§9) is the next artifact.

**Before writing spike code, do these three verifications in order:**

1. Open the [signalapp/libsignal releases page](https://github.com/signalapp/libsignal/releases) and pin a tag that actually exists. Resolve the A/B/C version conflict here.
2. Confirm the tag the current mobile apps ship, and decide your version-match strategy (same tag, or test the exact pair).
3. Read the store traits and session-function signatures in `rust/protocol/src/storage/traits.rs` and `rust/protocol/tests/` at that exact tag — do not trust the signatures quoted in this document without checking.

Then build the Rust↔Node harness first, prove Direction A + B + ratchet, and only then bring in Kotlin and Swift. Keep everything behind the serialized Tauri worker from §7. Do not hand-roll any crypto or wire format.

---

*Reconciled from three independent research passes (A, B, C) held in the project. Version numbers and function signatures reflect those passes as of 2026-07-21 and are explicitly flagged for re-verification. This document is design exploration only; no code is authorized against the Zitrone repository.*

