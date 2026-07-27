# U2 R1 — Grok adversarial review (blind)

**Unit:** U2 decoy envelope builder (`feat/0.10.0-decoy-u2-envelope-builder` @ `5e5b242f`, base `main` @ `2cd82a2b`)  
**Scope:** Whole unit — `DecoyEnvelopeBuilder.kt`, U2 delta to `DecoyIdentity.kt`, `DecoyEnvelopeBuilderTest.kt`, U2 spec corrections, U1 contracts the unit depends on (counter reservation, TAG_DECOY canonical trigger).  
**Method:** Source read of the whole unit + U1 dependencies; independent measurement against libsignal-client **0.46.0** (native probe, not the unit tests); server `ConsumeOneTimePrekey` SQL; real send path (`MessagingCoordinator`, `SignalProtocolManager`, `MessageEnvelope`, `WsClient.messageSendFrame`).  
**Not done:** Gradle unit tests failed in this environ# U2 R1 — Adversarial review (blind)

Reviewed the whole unit on `feat/0.10.0-decoy-u2-envelope-builder` @ `5e5b242f` (not a fix delta). Independently measured wire layout against **libsignal-client 0.46.0** (native probe). Gradle unit tests could not run here (JNA temp-file permission); mutation scores treated as claims only.

---

## Attack results (short)

| Target | Result |
|---|---|
| Byte-level shape vs real `SessionCipher` | **Holds** — structural bytes match; PreKey wrapper rebuilt from the builder layout is byte-identical to a real first message when keys/inner match |
| 323 vs 316; base64 padding | **Holds** (323→432/`=`; 316→424/`==`) |
| Counter varint @ 128 / 16384 | **Holds** in builder and real output |
| PreKey +81 B / frame +147 B | **Holds** (404 B first ciphertext; ~976 B frame) |
| `prekey_id = 1` | **Holds** — recipient OTP; `ORDER BY prekey_id LIMIT 1`; batch 1..100 |
| Counter reuse/regress | **No path found** via U1 reservation + builder spend order |
| Deniability (U2 surface) | **Holds** — unwired; no logs/prefs/slot names; only counter high-water |

Spec **numbers** U2 measured are right. Spec **prose application** is incomplete.

---

## Findings

### F1 — Stale **+39 B** residual under the corrected table
**Severity:** P3  
**File:line:** `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:128`

**Failure:** Table says first message **+147 B**; the next residual sentence still says X3DH is **+39 B**. Same doc-drift class as U1. Independent measure: wrapper **81 B**, frame delta **~147 B**.

**Why tests miss it:** Nothing asserts spec prose.

---

### F2 — §2.2 still promises **identical-size** paired frames with **821/1161**
**Severity:** P2  
**File:line:** `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:190-193` (echoes at `:30`, `:280`, `:342-348`)

**Failure:** Normative pairing text claims a real 821 B text yields an 821 B decoy and “two identical-size frames.” U2’s own measurements falsify that:

1. Modal subsequent frame is **829 B** (825 B if fractional seconds trim).
2. **`message_number` digit width** differs per conversation → same block count, different frame size (e.g. `5` vs `128`).
3. First-shaped vs subsequent differs by **~147 B**; U3 mirrors block count only.

U3 sequence: assert equal frame lengths per §2.2 → flake, or “fix” by constraining counters/shapes → new tell.

**Why tests miss it:** U2 gates compare real vs cover with the **same** counter/ttl/burn/blocks — single-envelope shape, not cross-conversation pairing.

---

### F3 — §3.3 still mandates **821 B** for dead-air
**Severity:** P3  
**File:line:** `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:342-348`

**Failure:** Callout says §3.3 inherits **829 B**; body still says **(821 B frame)**. U5 will inherit the wrong number.

**Why tests miss it:** U5 not built.

---

### F4 — Kdoc names non-existent `DecoyIdentityTest`
**Severity:** P3  
**File:line:** `DecoyIdentity.kt:76-78`

**Failure:** Pin is in `DecoyEnvelopeBuilderTest`, not a dedicated `DecoyIdentityTest`. Future agents may assume coverage that is not where kdoc points.

**Why tests miss it:** Behaviour is pinned elsewhere today.

---

### F5 — “14 gate tests” vs **13** `@Test` methods
**Severity:** P3  
**File:line:** spec U2 row `:678`; `DecoyEnvelopeBuilderTest.kt`

**Failure:** Status accounting overstates the suite.

---

### F6 — `blockCount` unbounded; Int overflow can wrong-size the body
**Severity:** P3  
**File:line:** `DecoyEnvelopeBuilder.kt:166-167,242-245`

**Failure:** Only `blockCount >= 1`. e.g. `blockCount = 16_777_216` wraps `blockCount * 256` to 0 → `bodyLength = 16`. Absurd input; U3 should pass real (small) block counts. Fail-closed upper bound would be safer.

**Why tests miss it:** Only blocks `1..4`.

---

### F7 — `registrationId` allows `0`
**Severity:** P3  
**File:line:** `DecoyEnvelopeBuilder.kt:141`

**Failure:** Real path draws `1..16380`. `registration_id = 0` in a first blob is off-distribution. Should fail closed like `ensureIdentity`.

**Why tests miss it:** Fixture uses `9142`.

---

## Explicitly clean (high-value checks)

- **Wire layout** verified against real 0.46.0: `0x34`; SignalMessage fields 1–4 + MAC; PreKey fields 1–6; `previous_counter = 0` present as `0x18 0x00` (API has `getCounter()` only — no `getPreviousCounter()`; byte-diff is the right guard).
- **`prekey_id = 1`:** recipient id; SQL `ORDER BY prekey_id LIMIT 1`; generator/builder share `ONE_TIME_PREKEY_IDS`.
- **Counters:** no reuse/regress path through builder + `DecoyCounterReservation` (section lock, flush-before-spend, singleton allocator, clearAccount resets mark).
- **Deniability / unwired:** no production construction; no device storage/logging in U2 files; no Signal records.
- **TAG_DECOY land-on-disk:** not restated in U2 (canonical stays `VaultStateCodec` kdoc).

**Relay-only residuals** (subsequent after one first without inbound reply; monotonic counter vs ratchet resets; OTP never consumed) are stated and fall under approved §1 (decoys do **not** claim to hide from the relay).

**Spec corrections to ratify:** layout rewrite, 829/1169/976 table, U4 counter residual — **numbers yes**; then finish scrubbing 821/1161/+39 and the identical-size pairing claim.

---

**VERDICT: FINDINGS (0 P1, 1 P2, 6 P3)**

Full write-up: `l00prite/.l00prite/reviews/decoy-0.10.0/u2-r1-grok.md`.
 
**Severity:** P3  
**File:line:** `DECOY_TRAFFIC_0.10.0_SPEC.md:678` (U2 row); `DecoyEnvelopeBuilderTest.kt` (13 tests)

**Concrete failure:** Spec/ledger status overstates the suite. Not a product bug; confidence accounting error.

**Why tests don't catch it:** N/A.

---

### F6 — `blockCount` has no upper bound; Int overflow can yield a silently wrong body length  
**Severity:** P3  
**File:line:** `DecoyEnvelopeBuilder.kt:166-167,242-245`

**Concrete failure:**  
`require(blockCount >= 1)` only.  
`bodyLength = blockCount * 256 + 16` uses 32-bit arithmetic. Example: `blockCount = 16_777_216` → product wraps to `0`, `bodyLength = 16`. Builder emits a 1-block-scale ciphertext while the caller believed a huge envelope. Real `SessionCipher` for that plaintext size would not match.

**Realistic blast radius:** U3 should pass the real message's block count (small). Absurd inputs are a programming error, not a traffic-analysis vector on the intended path. Fail-closed with `require(blockCount <= …)` would be safer.

**Why tests don't catch it:** Tests only use `blocks in 1..4`.

---

### F7 — `Sender.registrationId` allows `0`; real path draws `1..16380`  
**Severity:** P3  
**File:line:** `DecoyEnvelopeBuilder.kt:141`; compare `SignalProtocolManager.kt:90-91`, `DecoyIdentity.generateIdentity`

**Concrete failure:** `require(registrationId >= 0)` accepts `0`. A first-shaped decoy with `registration_id = 0` inside the PreKey blob is outside the real Android distribution. Fail-closed `in 1..16380` would match `ensureIdentity` / `DecoyIdentity`.

**Why tests don't catch it:** Fixture uses `9_142`.

---

## Explicitly checked — no defect found

### Byte-level equivalence (libsignal 0.46.0 probe)
- Version byte **`0x34`** on first and subsequent.
- `SignalMessage` field order: ratchet key (1), counter (2), **previous_counter (3) = 0**, ciphertext (4), 8-byte MAC.
- `PreKeySignalMessage` field order: prekey id (1), base key (2), identity key (3), message (4), registration id (5), signed prekey id (6).
- 1-block subsequent @ counter 7: **323 B**.
- 1-block first @ reg 9142: **404 B** (= 323 + 81).
- Rebuilding outer PreKey with the builder's layout around a real inner message produced **byte-identical** ciphertext to libsignal.
- Structural subsequent compare: **11 fixed bytes** all match when ratchet key / body / MAC are treated as random regions (same methodology as the unit test).

### Length as function of value
- Builder encodes `message_number` as a protobuf varint in the blob and copies the same int to the cleartext field.
- `prekey_id` / `signed_pre_key_id` are constants `1` (1-byte varints); `registration_id` uses caller-supplied value with real varint width — correct if U3 passes the vault's real registration id.
- Body length is varint-encoded via `writeVarint` (not a fixed-width length).

### X3DH first envelope
- Shape gated on `counter == 0` only; later envelopes null `ephemeral_key` / `prekey_id`.
- Cleartext `ephemeral_key` / `prekey_id` / `message_number` are read back from (or equal to) the blob contents — not drawn independently.
- 33-byte type-tagged keys (`0x05 ‖ 32`); base64 44 chars, no padding.

### `prekey_id = 1`
- Spec + code: recipient OTP id, not sender.
- `DecoyIdentity.ONE_TIME_PREKEY_IDS = 1..100` is the single source for `generateBundle` and `FIRST_ONE_TIME_PREKEY_ID`.
- Server: `DELETE … ORDER BY prekey_id LIMIT 1` → lowest unconsumed id.
- Residual (documented, §1): nothing fetches the synthetic bundle, so id 1 is never consumed on the relay — relay-visible only; out of scope for the passive-observer goal.

### Counter reservation (U1 path U2 calls)
- Private ctor + `forRuntime` WeakHashMap singleton per runtime.
- `next()` under section lock; durable mark re-read every call; stale block abandoned.
- `flushBeforeAck` before RAM cursor advance; throw ⇒ no value issued.
- `clearAccount` resets `counterHighWater` to 0 under the same section lock (new peer must restart at 0).
- Builder: validation before `next()`; reservation failure aborts build.
- No second writer of counters in U2.

### Deniability
- No production construction of `DecoyEnvelopeBuilder` (unwired).
- No logging / SharedPreferences / slot naming in the U2 files.
- No `SessionBuilder.process`; test pins empty `signalRecords`.
- Only durable mutation: counter high-water via existing allocator.
- TAG_DECOY land-on-disk rules not restated in U2 code (canonical remains `VaultStateCodec` kdoc).

### Threat-model residuals (not findings under approved §1)
- Subsequent shape after a single first message without a synthetic inbound reply is **not** what unacked libsignal does (stays `PREKEY_TYPE` until reply). Unit intentionally emits subsequent after counter 0; justification is U4 ~30 ms reply + pairing size. Relay-visible; §1 says decoys do not defend against the relay.
- Monotonic `message_number` never resets on inbound ratchet turns — stated U4 residual.
- Counter 0 reserved but unspent ⇒ conversation never emits first-shaped envelope — stated residual.

### Tests — would they fail if the named property broke?
| Test | Guards? | Blind spots |
|---|---|---|
| First / subsequent size + `shape()` | Length, base64 len/pad, exact non-random JSON fields | Same counter both sides; not pairing |
| Varint boundary | Length + shape at 126–129, 16383–16384; asserts real length actually moves | — |
| 33-byte ephemeral / base64 alphabet | Key size, pad, type tag, alphabet | — |
| Parse-back | reg id, signed id, identity, prekey, base key, counter, version | **No `getPreviousCounter()`** in libsignal Java API (confirmed via `javap`) |
| Byte-identical non-random | **Catches previous_counter / version / field order / tags** — the M13 class | Random regions must stay correctly derived; first-message `innerAt` assumes trailing field layout (matches real) |
| X3DH once | counter 0 only; resume from 64 | Conversation-level unacked multi-PreKey not in scope |
| prekey batch | generator ids == range; emitted id == min | Does not hit live SQL |
| No constants | ttl/burn/blocks vary; ids differ | Does not compare to real envelope for those fields (other tests do sizes) |
| Durable fail / no signal records / bad args | Reservation throw; signalRecords empty; no spend on bad args | Vault fixture needs libsodium |

**Mutation harness residual (claimed, not re-run):** restore-without-rebuild can leave stale classes — any "16/16 discriminated" claim is process evidence, not reproducible here.

---

## Spec corrections — ratification advice

| Correction | Verdict |
|---|---|
| §2.3 ciphertext is libsignal layout, not `random(32)‖random(12)‖AEAD` | **Ratify.** 316 was a total defeat class (base64 pad). |
| §2.1 table 829 / 1169 / 976 (+147) | **Ratify numbers.** Incomplete: body residuals and §2.2/§3.3 still carry 821/1161/+39. |
| §2.4 counter non-reset residual for U4 | **Ratify as residual** under §1 (relay-visible). |

---

## VERDICT: FINDINGS (0 P1, 1 P2, 6 P3)

**Code of the envelope builder is in good shape** for the unit gate (single-envelope indistinguishability vs a real message of the same block count and counter story). The serious remaining issue in this unit's delivery is **contract completeness**: U2 fixed the table and the implementation, then left the pairing/dead-air prose on the old numbers and the false "identical-size frames" claim — the same documentation-drift class that burned U1 for seven rounds.
