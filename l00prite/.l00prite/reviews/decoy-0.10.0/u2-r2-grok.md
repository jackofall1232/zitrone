I'll conduct a full adversarial security review of this unit, starting with the required design docs and then attacking the whole codebase (including removals) against the stated invariants.On the U2 branch. Loading the design docs and mapping the full unit surface.Reading the required design docs and the full unit source, without looking at the other reviewer's findings.Verifying the no-OPK first-message path against production encrypt output and the builder's hard require.# U2 Round 2 — Adversarial Security Review (blind)

Reviewed the full unit on `feat/0.10.0-decoy-u2-envelope-builder` @ `ebfe31f5` against source, the R2 review brief, `DECOY_TRAFFIC_0.10.0_SPEC.md`, and the U1/U2 invariant artifacts. Did not read the other reviewer's R2 findings.

---

## Finding 1 — P2

**File:line:** `DecoyEnvelopeBuilder.kt:234–236`, `249–273`, `345–353`, `437–458`; production shape at `SignalProtocolManager.kt:318–327`, `ApiClient.kt:218–230`; false pin at `DecoyEnvelopeBuilderTest.kt:676–678`

### Concrete failure

A real first message can carry **`ephemeral_key` set and `prekey_id` null**. That is signed-prekey-only X3DH when the peer’s one-time prekeys are exhausted.

Production already models this:

1. `GET …/prekey` may omit `one_time_prekey` → `preKeyId = null` (`ApiClient.kt:218–230`).
2. `establishSession` passes `preKeyId ?: -1` and a null one-time key (`SignalProtocolManager.kt:303–304`).
3. First `encrypt` is still `PREKEY_TYPE`; `EncryptResult.preKeyId` is null when `preKeyMessage.preKeyId` is absent (`SignalProtocolManager.kt:323–327`).
4. Protocol docs say the same: *“null if no OPK was available”* (`packages/crypto/src/x3dh.ts:35–36`).

Sequence:

1. Contact’s OTP batch is empty (or this fetch returns none).
2. User sends first message to that contact → real envelope: `ephemeral_key ≠ null`, `prekey_id = null`, ciphertext is a `PreKeySignalMessage` **without** protobuf field 1.
3. U3 calls `DecoyEnvelopeBuilder.build(sender, syntheticId, cover)`.
4. Line 234–236 throws: *“a real envelope carries ephemeral_key and prekey_id together or not at all”* — **false about real envelopes**.

Even if that `require` were removed, the first-shaped branch still:

- forces `requireNotNull(cover.preKeyId)` (253),
- always writes protobuf field 1 (`preKeySignalMessageBytes`, 447–448),
- sizes the wrapper with `1 + varintLength(preKeyId)` (`preKeyWrapperFixedBytes`, 347–348),
- places the base key with `baseKeyOffset(preKeyId)` assuming field 1 is present (465).

So a legitimate no-OPK first envelope is not representable: either an early throw, or a wrong wrapper layout and a frame-size failure.

Outcome when U3 is wired: **no matching cover** for that send (unpaired real frame), or a failed send if the throw is not isolated. Fail-closed avoids a size mismatch, but it still drops cover on a production shape.

### Why existing tests do not catch it

- Every first-shaped fixture uses `RealPath`, which always stores OTP id 1 and builds a bundle **with** that OPK (`DecoyEnvelopeBuilderTest.kt:114–128`). The gate never constructs a no-OPK session.
- The cross-product size test only sees OPK-present first messages.
- Lines 676–678 pin the **wrong** property: `real.copy(preKeyId = null)` is treated as “half a first message” and is required to throw. That fixture is also **internally inconsistent** (cleartext null id, ciphertext still has field 1). It never builds a true no-OPK encrypt, so it cannot discriminate “reject garbage” from “reject a real shape.”
- Mutation of the require to allow `(ephemeral ≠ null, prekey = null)` would not be forced red by any current success path.

---

## Finding 2 — P3

**File:line:** `DecoyAccountProvisioner.kt:329–330`

### Concrete failure

Comment still says holding the section monitor across PoW/HTTP would *“stall the counter allocator on the send path.”* The allocator is gone (R2). The real remaining reason to stay unlocked is still valid (token writers / other provisioner sequences / send path latency under `DecoySectionLock`), but the comment documents a deleted component as if it still constrained lock scope.

### Why tests do not catch it

Comments are not executed. No test asserts kdoc accuracy.

---

## Finding 3 — P3

**File:line:** `l00prite/.l00prite/reviews/decoy-0.10.0/u1-invariant-table.md` (signal field table ~L49–55, W3/W4/R2/R3 narrative ~L79–80, L125–141); contrast `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md` §3.0 / W3–W4 retired rows and `DecoyState` kdoc `VaultState.kt:141–148`

### Concrete failure

The U1 invariant table still presents `counterHighWater` and `deadAirNextFireAtMs` as fields of `TAG_DECOY`, and still narrates W3 (allocator) / W4 (dead-air) / R2–R3 counter readers as live design. U2 R2 **deleted** those fields and the allocator; the approved spec and `DecoyState` say not to re-add a paired-path counter. A later unit that treats the U1 table as current can reintroduce unreachable durable writers the R2 cut removed.

The U2 decision doc is marked superseded for this; the U1 table was not closed out the same way.

### Why tests do not catch it

Docs only. Codec tests assert the **current** five-field body (700 B raw), not table freshness.

---

## Attacks from the brief (resolved against source)

### 1. Frame-equality postcondition (`build` L298–301)

- Measures production `WsClient.messageSendFrame` → UTF-8 size; send path uses the same `frame.toString()` (`WsClient.kt:211–212`). Consistent.
- Size-affecting envelope fields: `message_number`, shape (`ephemeral_key`/`prekey_id`), ciphertext **byte** length, timestamp **width**, `ttl_seconds`, `burn_on_read`, `media_type`, `version`, `previous_chain_length` are mirrored or width-matched; `id` is UUID vs UUID (36); `recipient_id` width is required; `sender_id` must equal `sender.accountId`.
- Blob-internal mismatches (`signed_pre_key_id`, `previous_counter`) are absorbed in the AEAD body; documented §2.4 residual (relay-visible body non-multiple, not a passive TLS size tell).
- Postcondition cannot “pass while sizes differ to a passive observer”: it is total frame length, which is what that observer measures. Content is supposed to differ.
- **Throws on a legitimate pair:** yes — Finding 1 (no-OPK first message). That is fail-closed, not a silent mismatch.
- Compensating errors (e.g. wrong digit width cancelled by wrong timestamp width) are hard under construction: ciphertext length is forced equal, so base64 length cannot float to hide other digit errors.

### 2. `DecoySectionLock` still earns its place

**Verified.** Remaining multi-call sequences that take it:

| Sequence | Lock |
|---|---|
| `storeTokens` / `storeTokensForAccount` read-then-write | yes |
| `clearTokens` / `clearAccount` | yes (exclusion vs above) |
| `reserveBackoff` mutate+flush | yes |
| `clearBackoff` compare-and-clear | yes |
| provision commit: read `beforeCommit` → mutate → flush → capacity `revertSection` | yes |

Single atomic reads (`hasAccount`, `isDeferred`, token getters, `readCredentials`) correctly skip it. All production `state.decoy =` writers are under those paths. No re-opened TOCTOU found for remaining sequences. Allocator-only justification is gone; auth + provisioner sequences remain real.

### 3. Deleted negative-counter tests / encode–decode symmetry

Property “negative `counterHighWater` refused both ways” is **obsolete** (field gone). The **symmetry principle** (refuse to produce what you refuse to read) is still pinned by the credential half-set pair on encode **and** decode (`VaultDecoySectionTest` R4 tests + `requireDecoyCredentialsPaired`). That is sufficient for the principle. Coverage of a dead field correctly narrowed; no silent loss of a live invariant found.

### 4. Retargeted tests

- **Nullable-long canonicity** → `provisionNotBeforeMs`: still exercises `readNullableLong`/`writeNullableLong`; offset tripwire (`decoySectionTailIsWhereThisSaysItIs` / `DEFERRAL_PRESENCE_FROM_END = 9`) keeps tampers on the right byte. Still discriminates for the named property.
- **Concurrency / capacity revert**: concurrent writer is a section-locked `provisionNotBeforeMs` write during `register`; assertion requires `concurrentDeferral` (not the write-ahead value). Still discriminates “commit-time snapshot under the same lock,” not merely “some mutate ran.”

### 5. Codec after field removal

`TAG_DECOY` body: `accountId ‖ identityKeyPair ‖ accessToken ‖ refreshToken ‖ provisionNotBefore` (`encodeDecoy`/`decodeDecoy`). `isEmpty` includes all five; empty holder omitted (0.9.x readability). Trailing bytes, duplicate tags, truncation, unknown tags, half-sets, noncanonical nullable-long: covered. No migration code assumes shipped `0x06` — comments correctly state unshipped field-set change. **No codec defect found.**

Raw worst-case body **700 B** (deterministic, test-asserted; measured in last run). Encoded delta is a **distribution** under DEFLATE + fresh identity material (run sample 638 B; suite notes 636–646 B). Budget 1024 B remains a bound, not a point estimate.

### 6. Round-1 surface (byte layout, varints, X3DH, keys, deniability)

- Structural byte-diff vs real `SessionCipher` output present; `previous_counter=0` pinned by that test (length tests alone cannot see it).
- Counter varint boundaries 126–129 / 16383–16384 tested against real sessions.
- Keys: `Curve.generateKeyPair().publicKey.serialize()`, high-bit canonicity tested; private half GC residual documented (same class as `DecoyIdentity`).
- X3DH shape mirrored from cover; first vs subsequent size delta 147 B pinned — **except no-OPK first shape (Finding 1)**.
- Builder has **no** vault/`SessionBuilder` access; deniability: no device-level decoy storage; sealed region fixed-size; section omitted when empty.
- Counter allocation removed; paired path mirrors `message_number` — regression/reuse of a durable decoy counter is no longer expressible.

### Invariants (U1 + U2)

| Invariant | Verdict |
|---|---|
| Register-before-commit | Holds: staging store, one mutate+flush, fail-closed id setter, codec half-set refuse |
| Counter skip-never-regress | **N/A** after R2; mirror replaces reservation |
| Key material wipe | Holds on wipe/decode-fail/encode buffers; libsignal private-key GC residual documented |
| Deniability / sealed image | Holds for durable surface; no vault-count oracle in this unit |
| Strict-v1 codec | Holds |
| Capacity / durability | Holds; budget bound honest post-R2 |
| Mutation / lock order | Holds for remaining writers |
| Presence ≠ readiness | Holds (`isProvisioned` / `hasAccount` vs section presence) |
| Registration scarcity | Holds (latch, write-ahead backoff, silent degrade) |

### Test discrimination notes

- Gate + postcondition double-cover frame equality for shapes actually fixtureable.
- Canonical-key test discriminates `0x05‖random(32)` via impostor high-bit rate.
- Half-set encode/decode pair discriminates pairing rule.
- **Blind spot same family as M13:** no-OPK first message is invisible to a suite that only ever builds OPK-present X3DH (Finding 1).

---

## VERDICT: FINDINGS (0 P1, 1 P2, 2 P3)
