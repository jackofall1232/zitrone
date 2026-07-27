# U1 — ROUND 4 adjudication (Codex + Grok, paired-blind)

Codex: `FINDINGS (0 P1, 2 P2, 2 P3)` · Grok: `FINDINGS (0 P1, 1 P2, 3 P3)`
Union after dedup: **0 P1, 2 P2, 4 P3.**

## Convergence — now strong

| Round | Findings (union) | P1 | Reviewer agreement |
|---|---|---|---|
| 1 | 10 | 2 | fully disjoint |
| 2 | 11 | 1 | 2 of 11 |
| 3 | 10 | 0 | top 3 independently |
| 4 | **6** | **0** | **top 2 independently, with the same proposed fix** |

Findings falling, severity floored at zero for two consecutive rounds, and the reviewers now
independently reaching the same conclusions *and the same remedy*. Per the calibration note in
`failures.md`: this is the surface stabilising. Both still found items the other missed, so it is not
anchoring.

## CONFIRMED — must fix

| # | Src | Sev | Defect |
|---|---|---|---|
| J1 | **both** | P2 | **The spent/not-spent discriminator is set one line too early.** `registrationSpent = true` precedes `relay.register(DecoyIdentity.generateBundle(identity), powProof)` — and Kotlin evaluates the argument *after* the flag is set. `generateBundle` is 101 local keypairs and signatures: **pure local crypto, zero bytes to the relay.** If it throws (OOM on the batch, provider failure), the catch treats the registration as possibly spent, skips `clearBackoff`, and the vault takes a 60–90 min silence **plus** a durable `TAG_DECOY` and the 0.9.x break — having never contacted the relay. Both reviewers propose the identical fix: hoist the bundle, set the flag between generation and the call. The hinge comment itself says the flag exists because **register** may have created the account; `generateBundle` is not register. |
| J2 | Codex | P2 | **The codec does not enforce credential-pair integrity.** `DecoyState(accountId = "…", identityKeyPair = null)` encodes and decodes cleanly — exactly the dangling account reference the register-before-commit invariant claims is structurally impossible. Key-only and token-only states are likewise accepted. `isProvisioned`/`hasAccount` merely *hide* the malformed state rather than the codec rejecting it. Strict-v1 should refuse to produce or accept what its own central invariant forbids. |
| J3 | **both** | P3 | **§4.1 still understates the break** — see the ruling below. |
| J4 | Grok | P3 | **§6.2a states round-2 semantics as current law**, directly contradicting round-3 code: "only a successful commit retires" the deferral, "*every* failure defers", "an offline challenge fetch … costs a 60–90 minute wait". Round 3 made pre-register failures retire the deferral. **Stale contract describing removed behaviour — fourth recurrence of a class `failures.md` already records.** |
| J5 | Codex | P3 | The invariant table's primary WRITER inventory omits `clearBackoff`, a genuine durable writer (`mutate` + `flushBeforeAck`). W1 still says success is the only retirement path; the corrected behaviour appears only in later round-3 notes. The approved spec repeats the stale contract in two more places. |

## J3 — the disclosure, third pass. My own proposed fix was ALSO wrong.

Grok's truth table is what settles it. When does `TAG_DECOY` actually become durable?

| Path | Tag on disk? |
|---|---|
| Never calls `provisionIfNeeded` | no |
| Fails **before** `register`, deferral retired | no — emptied holder is omitted |
| **Reaches `register`** (including 429, or a lost response) | **yes** |
| Succeeds, never sends a decoy | **yes** |

So the trigger is **setup that reaches relay registration** — not a completed send, and not a send
attempt either.

**I proposed "the first time it *tries to* send any" and that is wrong in the other direction.** It
overstates: a vault that tries, fails offline before `register`, and retires its deferral keeps full
0.9.x readability. I was correcting an understatement and would have shipped an overstatement.
Recorded because it is the same trap the maintainer named — the wording keeps drifting because its
truth depends on implementation detail, and each pass I have reasoned from the *last* wording rather
than from the code's actual behaviour.

**Proposed final wording, accurate on all four rows:**

> **What 0.10.0-beta specifically changes:** once a vault has **set up cover traffic** — which
> happens the first time it sends any, and is complete as soon as its cover-traffic account is
> registered — it can no longer be opened by 0.9.x; downgrading will present that vault as corrupt.
> A vault that has never used cover traffic, or whose setup never reached the relay, is unaffected.

Applied with a **PENDING RE-RATIFICATION** marker rather than left understating, since an
understated format-break disclosure is the more dangerous direction and this must not sit false
while it waits. The maintainer has ratified this sentence once already; this is the third pass and
the reason is recorded above.

## Note for the fix brief

J4 and J5 are both stale contracts, and J3 is a stale user-facing claim. **Three of five findings
this round are documentation that drifted from behaviour, not code defects.** The code is
converging; the prose describing it is now the lagging surface. `failures.md` already records
"when a change removes or alters behaviour, update its doc/contract/spec in the SAME change" — this
round is that rule being broken three times in one unit. Sweep every contract that describes the
back-off lifecycle, not only the ones the reviewers happened to cite.
