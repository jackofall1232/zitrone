I'll conduct a full adversarial security review of this unit end-to-end. Starting with the project protocol and required docs, then reading every in-scope source and test against the invariants.This is U2 on the envelope-builder branch. I'll review the full unit end-to-end, starting with the R3 change surface and required contracts.Reading the envelope builder and tests in full—that's the R3 attack surface—then the rest of the unit.Continuing with VaultState decoy codec, provisioning, and contracts—especially post-deletion field claims.Verifying the no-OPK path against real libsignal layout and scanning for remaining actionable contradictions.Checking one last structural boundary—whether the no-OPK cleartext path can disagree with protobuf sizing when `preKeyId` is remapped:# Adversarial Security Review — U2 Round 3 (blind)

**Scope reviewed:** whole unit on `feat/0.10.0-decoy-u2-envelope-builder` vs `a4f118df` — `DecoyEnvelopeBuilder` + tests, full U1 surface (`VaultState`/`DecoyState`/`TAG_DECOY`, `DecoyAuthStore`, `DecoyIdentity`, `DecoyRelayApi`, `DecoyAccountProvisioner`, `DecoySectionLock`), gate tests, and contracts (`DECOY_TRAFFIC_0.10.0_SPEC.md`, `u1-invariant-table.md`, `VAULT_ARCHITECTURE.md` §3–§8).

**Note:** Gradle wrapper could not run here (`Permission denied` on the dist lock), so runtime pass/fail is inferred from source + production call sites, not from a green suite in this environment.

---

## R3 attack surface — verified against source

### 1. No-OPK path end-to-end

Production path is consistent with the claimed shape:

- `ApiClient.fetchPreKeyBundle` → nullable `one_time_prekey`
- `SignalProtocolManager.establishSession` → `preKeyId ?: -1` with null key
- `EncryptResult` → always sets `ephemeralKeyBase64` on `PREKEY_TYPE`; `preKeyId` only when `preKeyId.isPresent`
- `packages/crypto/src/x3dh.ts` → `usedPrekeyId: number | null` (“null if no OPK was available”)

Builder four-site change, single `id` binding:

| Site | Behaviour when `preKeyId == null` |
|---|---|
| `require` | implication only: `preKeyId != null ⇒ ephemeralKey != null` |
| `preKeyWrapperFixedBytes` | charges **0** for field 1 |
| `preKeySignalMessageBytes` | skips tag `0x08` entirely |
| `baseKeyOffset` | `1 + 0 + 1 + 1 = 3` (value after version + base-key tag + length) |

Same `id` is passed into sizing, serialization, offset read-back, and cleartext. An off-by-two between sites would fail `check(ephemeralKey.contentEquals(baseKey))` or `check(blob.size == target)` before return. Tests build fixtures via genuine `PreKeyBundle(…, -1, null, …)`, assert `realBlob[1] == 0x12`, parse-level field absence, and byte-identical structure for both OPK variants — not merely length equality.

### 2. OPK ↔ no-OPK frame interaction

Ciphertext raw body differs by 2 B (tag + 1-byte id varint for id∈1..100). Cleartext `"prekey_id":null` vs a number also moves the JSON. The gate cross-product covers both shapes; the dedicated no-OPK test asserts the two variants’ frames are **not** equal. The builder always derives shape from the covered envelope’s cleartext, so it cannot silently emit the wrong variant for a given cover. Wrong pairing would be a U3 bug; U2 fails closed on frame mismatch.

### 3. `preKeyId` present, `ephemeralKey` null

**Unreachable from the real encoder.** `EncryptResult` for `PREKEY_TYPE` always copies the base key into `ephemeralKeyBase64`; subsequent messages set both null. The remaining guard is the correct half of the implication. Tested only via mutated fixtures — appropriate.

### 4. Require relaxation (`copy(preKeyId = null)` no longer throws)

**Judgement: correct.** The builder is a cleartext-mirroring shaper that never decodes ciphertext by design. Re-imposing “both or neither” would re-break legitimate no-OPK production traffic. Accepting a *mutated* OPK ciphertext with cleartext `prekey_id = null` yields a self-consistent no-OPK-shaped cover at the covered byte length (body absorbs the 2 B); that inconsistent fixture is not a production `EncryptResult`. No real guard was lost.

### Carried invariants (U1 + residual U2)

| Claim | Result |
|---|---|
| Register-before-commit | Staging store + one mutate/flush; crash ⇒ orphan or complete set |
| Counter reservation | **Deleted** with ping; builder mirrors `message_number` |
| Key wipe | Decode-failure wipe of identity key; `VaultState.wipe` → `DecoyState.wipe`; handedOff discipline on capacity |
| Deniability / fixed image | Section inside sealed fixed region; empty omitted; no device-level storage; unit unwired |
| Strict-v1 codec | Unknown tag throws; `requireDecoyCredentialsPaired`; trailing bytes refused; raw body 700 B tripwire |
| Presence ≠ readiness | Readers use `isProvisioned` / credential pair, not section presence |
| Registration scarcity | Per-runtime latch, write-ahead backoff, silent degrade, one attempt |
| Section lock | Survives; order `section → stateLock → session → storage`; no reentrancy from persist sinks |

`DecoyState` kdoc is treated as field-set canonical; invariant table and spec mark deletions with `[U2R3]` / struck rows. No remaining **code** claim of `counterHighWater` / `deadAirNextFireAtMs` / `DecoyCounterReservation`.

---

## Findings

### P3 — Spec still binds a key construction the code correctly abandoned

- **Severity:** P3 (contract/doc; would reintroduce a known fingerprint if followed)
- **File:line:** `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:194` (and residual operational prose at `:159`)
- **Concrete failure:**  
  R7 still states as a binding instruction: **“U2 must emit `0x05 ‖ random(32)`.”**  
  Live code does the opposite on purpose:

```527:527:apps/android/app/src/main/java/com/zitrone/app/decoy/DecoyEnvelopeBuilder.kt
    private fun coverPublicKey(): ByteArray = Curve.generateKeyPair().publicKey.serialize()
```

  Class kdoc (`DecoyEnvelopeBuilder.kt:171–180`, `:523–526`) documents that `0x05 ‖ random(32)` is **not** a valid Curve25519 encoding (~½ of points have bit 255 set). Implementing the still-live R7 sentence re-ships the round-1 G-B distinguisher: structurally impossible public keys in cover envelopes.

  Separately, after striking the false biconditional, §2.2 still leaves live text “**emit well-formed-looking values exactly once at setup, null thereafter**” (`:159`). That reads as “always emit both first-message fields once,” which is the same false model R3 corrected (no-OPK first messages never emit `prekey_id`). The CORRECTION block below fixes the implication, but the unstruck operational sentence was not rewritten.

- **Why existing tests do not catch it:** Tests pin **code** behaviour (canonical keys, no-OPK coverage). Nothing asserts that the approved spec’s binding sentences match the code. This is the same “parallel copy survives the fix” class that seeded G2-A and has recurred on this feature.

---

## Explicit non-findings (pressed and closed)

1. **Four-site off-by-two** — single `id` flows through wrapper size, bytes, offset, cleartext; read-back `check` + total-size `check` bind the boundary.
2. **Relaxation of the require** — right direction; reverse half remains enforced; production cannot produce `preKeyId` without `ephemeralKey`.
3. **Cross-product frame equality** — variants differ; same-shape cover matches; wrong shape cannot return.
4. **Byte-level vs length-only** — layout test runs both OPK arms; dedicated no-OPK test uses genuine encrypt, not `copy(preKeyId=null)`.
5. **Varint 128 / 16384, prekey width, media_type/`file`, previous_chain_length, version** — covered by gate / dedicated tests that would fail under hardcoding.
6. **Manufactured “maybe racy” items** — section lock + gate scope look correct for remaining RMW sequences; no counter allocator left to regress.

---

`VERDICT: FINDINGS (0 P1, 0 P2, 1 P3)`
