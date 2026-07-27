I'll run a documentation-only confirm pass on commit `364fe150`: verify the replacements against the real construction path, sweep for stale binding claims (including correction blocks), and check canonicity plus that the commit is docs-only.Commit is docs-only. Next I’ll ground the two replacements in `DecoyEnvelopeBuilder` and the real send path, then claim-sweep the spec and related docs.Digging into remaining binding claims, canonicity kdocs, and the real send path.Checking one residual “both fields” paraphrase and confirming correction blocks hold nothing abandoned.## Confirm pass — `364fe150` (docs only)

### Commit surface
Docs/memory/reviews only: `DECOY_TRAFFIC_0.10.0_SPEC.md`, `failures.md`, three review artefacts. **No code** under `apps/`, `packages/`, or `server/`.

---

### Replacements vs code / real send path

**1. Mirror the covered envelope (shape)** — **true**

- Real path (`SignalProtocolManager.encrypt`): `PREKEY_TYPE` always sets `ephemeralKeyBase64` from `baseKey.serialize()`; `preKeyId` only when `preKeyId.isPresent`; subsequent messages set both null.
- Builder (`DecoyEnvelopeBuilder.build`): shape is taken from `cover.ephemeralKey` / `cover.preKeyId`; `require` is one-way (`preKeyId != null ⇒ ephemeralKey != null`); no “emit both once at setup.”

**2. Key construction via `coverPublicKey()`** — **true**

```527:527:apps/android/app/src/main/java/com/zitrone/app/decoy/DecoyEnvelopeBuilder.kt
    private fun coverPublicKey(): ByteArray = Curve.generateKeyPair().publicKey.serialize()
```

Matches the R10 formula. Code and kdoc already reject `0x05 ‖ random(32)`.

---

### Abandoned constructions still live as binding?

| Claim | Status |
|---|---|
| `U2 must emit 0x05 ‖ random(32)` | Struck; only appears as struck history + “NOT this” in code kdoc |
| “emit both well-formed fields once, null thereafter” | Struck |
| Ciphertext `random(32)‖random(12)‖…` | Still only as measured-wrong history; wire layout canonical is `DecoyEnvelopeBuilder` |
| `build(blockCount)` | Superseded by covered-envelope interface |

Correction blocks re-read end-to-end: remaining live rules (implication not biconditional; size to `PreKeySignalMessage`; mirror counter; `prekey_id` batch with residual 3/4) match the builder, not abandoned paths.

---

### Canonicity

Three designations, **non-overlapping**:

| Artefact | Scope |
|---|---|
| `VaultStateCodec` kdoc | When `TAG_DECOY` is written |
| `DecoyState` kdoc | Field set of `TAG_DECOY` |
| `DecoyEnvelopeBuilder` (+ `coverPublicKey` / wire-shaping) | Envelope construction |

No contradiction, no gap for U2 construction. Mild note only: the R10 correction still restates `Curve.generateKeyPair()…` as “the rule” while also pointing at the canonical method (and while `failures.md` in the same commit says correction notes should not carry binding instructions). Formula matches code; not a false abandoned construction.

---

### Finding

**P3 — `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:129-130`**

Live text (outside any strike/correction):

> “the X3DH first message is larger … **with two fields flipping non-null**.”

That is the same false pair model R10 struck at §2.2: a first envelope always flips `ephemeral_key`; `prekey_id` may stay null (signed-prekey-only X3DH). Size-tell claim still holds; “two fields” does not. Not a construction `must`, but an unstruck paraphrase of the defect R10 fixed.

Invariant table / `VAULT_ARCHITECTURE.md` / builder kdoc: no remaining live `0x05‖random(32)` or “emit both once” binding.

---

**VERDICT: FINDINGS (0 P1, 0 P2, 1 P3)**

The two R10 replacements are accurate against the builder and the real send path. The only live residue of the corrected model is the “two fields flipping non-null” gloss at §2.1.
