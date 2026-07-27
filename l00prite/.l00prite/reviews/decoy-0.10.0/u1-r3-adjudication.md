# U1 — ROUND 3 adjudication (Codex + Grok, paired-blind) — CONVERGING, not yet converged

Codex: `FINDINGS (0 P1, 5 P2, 3 P3)` · Grok: `FINDINGS (0 P1, 2 P2, 2 P3)`
Union after dedup: **0 P1, 6 P2, 5 P3.**

## Convergence signal — the first real one

| Round | P1 | Top findings |
|---|---|---|
| 1 | 2 | fully disjoint between reviewers |
| 2 | 1 | 2 of 11 convergent |
| 3 | **0** | **the top 3 are the SAME findings, found independently** |

Rounds 1–2 the reviewers found disjoint sets — the argument for running a pair at all. Round 3 they
independently landed on the same three defects (the disclosure break, the two-instance provisioner,
and the instance-scoped flush flag). Agreement after independent disagreement is the signal that the
surface is being exhausted, not that the reviewers got lazy: each still found items the other missed.

## CONFIRMED — must fix

| # | Src | Sev | Defect |
|---|---|---|---|
| H1 | **both** | P2 | **THE §4.1 DISCLOSURE IS NOW FALSE — maintainer-facing.** `reserveBackoff()` does `mutate` + `flushBeforeAck` of a deferral-only section **before any relay I/O** (`DecoyAccountProvisioner.kt:363-378`, verified). So one `provisionIfNeeded()` that fails offline — zero cover traffic, zero registration — leaves a non-empty `TAG_DECOY` on disk, and a 0.9.x build refuses that vault as corrupt. §4.1 says "a vault that has **never generated cover traffic** is unaffected and still opens on 0.9.x." False. The codec kdoc carries the same false claim, and the invariant table notes the earlier trigger but wrongly concludes the disclosure still holds. |
| H2 | **both** | P2 | **The provisioner never got the structural-uniqueness treatment the allocator got.** Two instances over one runtime each hold their own `attempted` latch → both pass the deferral check, both register, last commit wins: one orphan and **two global-bucket spends for one vault**. Round 1 already ruled kdoc-only uniqueness is not a defense and fixed `DecoyCounterReservation` with a `forRuntime` registry; the provisioner was left on a public constructor. |
| H3 | **both** | P2 | **`credentialsUnconfirmed` is instance-scoped — wrong scope.** A second provisioner over the same live runtime defaults the flag to false, so `canSend()` returns **true** on credentials whose flush threw. G2 was closed only for the instance that witnessed the throw. |
| H4 | Codex | P2 | **`refreshTokens()` repeats the stale-read-across-network shape.** It snapshots identity + refresh token, blocks on the relay, then `storeTokens()` — meanwhile `clearAccount()` wipes the account. The response materializes a token-only `DecoyState`, **restoring valid bearer credentials for a cleared account**. The section lock protects each write but not this read→network→write sequence, which is exactly the defect class round 2 was supposed to have eliminated. |
| H5 | Codex | P2 | **Deferring on *every* failure is too broad.** A transient offline challenge fetch, DNS failure, relay restart, or PoW failure now disables cover traffic for 60–90 min while protecting **nothing** — no registration was spent. Round 2 chose one rule over a rollback branch to avoid branchiness; correct instinct, over-applied. |
| H6 | Codex | P3 | `parsePlaintext` reads `r.u8()` and checks the version **before** the `try`, so a throw there skips `partial.wipe()`. Production passes a fresh accumulator so blast radius is the seam's own contract, not live key material. |
| H7 | Codex | P3 | **Encoder/decoder asymmetry:** the decoder rejects negative `counterHighWater`, the encoder happily emits it. Strict-v1 should refuse to *produce* what it refuses to *read*. |
| H8 | Grok | P3 | `DecoyState.provisionNotBeforeMs` kdoc still says "set only when the relay answers 429". After round 2 it is written before any relay contact on every attempt. **Stale contract describing removed behaviour — `failures.md` records this exact class as having recurred twice already.** |
| H9 | Codex | P3 | `clearer.join(30_000).let { true }` is unconditionally true, including when the thread is still alive. The assertion named "the clearer finished" asserts nothing. Should be `assertFalse(clearer.isAlive)`. **Fourth occurrence of the non-discriminating class in this unit.** |
| H10 | Grok | P3 | A test comment claims "the next unlock over the SAME image spends nothing either" while the code builds a **fresh** `stateFilledToCap()`. The comment describes a stronger property than the test exercises. |

## Architect's ruling on H1 + H5 — one fix, not two

They share a cause: round 2 made the pre-network back-off write **unconditional and permanent**.

The write itself is worth keeping — it is a genuine capacity gate, and the reason it exists is sound:
*if the smallest possible decoy write will not encode, no registration is spent.* That is what closed
round 2's G4. Deleting it reopens "one registration per unlock at absolute capacity."

**The defect is not that it is written, it is that it is never retired on a failure that spent
nothing.** So: **clear the deferral when the attempt fails BEFORE any registration is spent.**

- Capacity: the deferral cannot be written → no registration is attempted. Unchanged. ✅
- Transient (offline, DNS, PoW, session mint): deferral written, attempt failed pre-registration →
  **clear it.** Cover traffic recovers on the next attempt instead of stalling an hour. Fixes H5. ✅
- Registration actually spent, then any failure: deferral **stays**. G4's protection intact. ✅
- Crash between write and clear: a spurious ≤90 min deferral. Accepted — it costs a background
  nicety, and the alternative costs a global registration.

And because an emptied section is **omitted entirely** (`takeUnless { it.isEmpty }`), clearing it
restores 0.9.x readability — so this also repairs H1 rather than papering over it.

## H1 — the disclosure wording, which is a MAINTAINER RULING and needs re-ratification

Even with the fix above, the honest trigger is **"this vault provisioned a cover-traffic account"**,
not "generated cover traffic". Those are nearly the same thing in practice — U3 provisions lazily,
from the first session that actually needs a decoy — but they are not identical: a vault that
registers and then never sends still carries the tag.

Recommended §4.1 wording, minimally broadened and accurate:

> once a vault has **set up cover traffic** — which happens the first time it sends any — it can no
> longer be opened by 0.9.x. A vault that has never used cover traffic is unaffected.

**Flagged for the maintainer rather than silently rewritten**, because the narrowing was their
explicit ruling and their stated reason was that an overstated disclosure is its own dishonesty. An
understated one is worse, so this must not simply be left as-is either.

## Note for the fix brief

H2/H3/H4 are all "the guard's scope does not match the resource's scope" — the exact lesson
`failures.md` records from 0.9.2 PR-3 ("match the guard's scope to the resource's scope"). The
allocator was fixed that way in round 1; the provisioner and the token-refresh path were not.
Fix them the same way rather than inventing a third pattern.
