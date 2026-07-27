# CONFIRM PASS — Zitrone 0.10.0-beta, Unit U2. Documentation only.

Short, tightly scoped. **This is not a re-review of the unit.** U2's code converged in round 3: two
independent blind reviewers each returned **0 P1 and 0 P2**, one of them `VERDICT: CLEAN` across the
full unit. The code is settled.

## What this pass is for

Round 3's only finding was **spec text**, and the architect's fix for it is **unreviewed**. The
architect's unreviewed documentation edits have been found wrong three separate times on this
feature, so they get a pass before the unit merges.

See the change with: `git show 364fe150`

## What changed

Two sentences in `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md` were struck, plus a `failures.md` entry.

1. **`**U2 must emit `0x05 ‖ random(32)`.**`** — struck. It was a live binding instruction sitting
   *inside the correction block written to fix that very defect*. That construction is not a valid
   Curve25519 encoding: genuine public keys have bit 255 clear, random bytes set it ~50% of the time
   (measured 0 of 200 real keys). Following it re-ships round 1's P1. Replaced with a pointer to
   `DecoyEnvelopeBuilder.coverPublicKey()`, now declared **canonical for construction**.
2. **"emit well-formed-looking values exactly once at setup, null thereafter"** — struck. It encoded
   the false model round 3 corrected: a real first envelope may carry `ephemeral_key` with
   `prekey_id` **null** (signed-prekey-only X3DH, peer's one-time prekeys exhausted). Replaced with
   **"mirror the covered envelope — do not construct a shape from a description."**

## What to check

- **Are the replacements true?** Read them against `DecoyEnvelopeBuilder.kt` and the real send path.
  Not against the previous wording — that is the specific error that produced four bad versions of a
  different sentence on this same feature.
- **Is anything still binding a construction the code abandoned?** Sweep the spec, kdoc, the
  invariant table and `VAULT_ARCHITECTURE.md` **by claim, not by phrasing**. Search for paraphrases
  ("random 32 bytes", "type-tagged random", "both fields", "always emit"), and for tables or lists
  whose *omissions* carry the claim.
- **Correction blocks specifically.** The lesson from round 3 is that a correction note is a parallel
  copy by construction and is the copy least likely to be re-read, because its heading announces the
  problem as solved. **Treat every correction/adjustment block in the spec as unreviewed ground** and
  check each for a stale binding claim.
- **Is the canonical-artefact designation coherent?** Three things now claim canonicity for different
  scopes: `VaultState.kt`'s codec kdoc (tag-write trigger), `DecoyState`'s kdoc (`TAG_DECOY` field
  set), and `DecoyEnvelopeBuilder` (construction). Do they overlap, contradict, or leave a gap?
- **Did any code change sneak into a documentation commit?** Verify.

## Output

Findings with severity, file:line, and the concrete inaccuracy. **If it is clean, say `VERDICT: CLEAN`
plainly — do not pad.** This unit is about to merge on the strength of this answer.
