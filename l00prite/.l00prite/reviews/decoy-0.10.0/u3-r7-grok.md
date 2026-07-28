I'll read the protocol docs and the decoy/cover-traffic implementation so the ruling is grounded in the actual design and residual claims.# 1. RULING

**Verdict: Position A is right on the meta-rule; Position B is right on the substance. Pure A and pure B are both wrong.**

The four mechanisms are real, agreed, and inherent to “two writes over one shared transport/budget.” They are **not** pairing-order bugs and **not** closed by more client-side cleverness in `DecoySendPairing`. They are also **not** “satisfied” by being declared residuals while R-U3-1 / R-U3-3 still say **Absolute**. Absolute wording that is false is a false claim. Documentation does not fulfill an absolute requirement; it only proves the authors noticed the conflict.

**Correct disposition:** rewrite the requirements to admit a **bounded residual class** (and ship under that wording). Do **not** treat the four as U3 code P1s to “fix” in the pairing layer. Do **not** keep shipping under absolute wording. “Do not ship the feature” is required only if, after honest residual declaration, cover is net-negative (high residual rate, or adversary-inducible marking / real-send DoS). That bar is not met by the residual *existence* alone.

---

### 1. Can a declared, tested residual satisfy a requirement declared ABSOLUTE?

**No.**

“Absolute” means: no reachable counterexample, no de minimis carve-out, no prose override. R-U3-1 already encodes that discipline (supremacy clause; “materially” only on delay; round-3 ruling that you restructure or **formally** change the spec). The implementer then did the opposite for residual classes: declared them, and in one case **tested the forbidden outcome as accepted** (`DecoySendPairingTest.kt:1380`).

That test is good engineering honesty about a trade. It is terrible compliance with an absolute rule. Those two roles cannot be the same sentence.

**Disposition hierarchy:**
1. Prefer constructions that make absolute claims true (real-first, confined teardown, build-then-admit, no caller fallback on `quiesce`) — already done for the fixable surface.
2. Where the property is **structurally unsatisfiable** on this transport, **relax the requirement explicitly**.
3. “Do not ship” only if the relaxed feature fails a net-value test (below), not merely because residual rate is nonzero.

Shipping under absolute wording unchanged is the only option that is always wrong.

---

### 2. Does R-U3-3’s rationale turn against the feature itself?

**The absolute form of the rationale overstates. The feature is not automatically condemned.**

Correct claim:

> **Client-controlled, sensitive-event-correlated, or adversary-inducible intermittency is worse than no cover.**  
> Uncorrelated residual unpaired reals at low rate are **not**.

Why the reductio fails:

| Regime | What a passive TLS observer sees | Net vs no cover |
|---|---|---|
| No cover | Every real send is a single of size \(X\) | baseline: all activity marked real |
| Perfect cover | Every real send is a same-length pair | best |
| Residual unpaired, **uncorrelated** (natural death mid-gap) | Mostly pairs; rare singles at ordinary network-failure moments | still better: most mass is paired; singles are no more informative than “socket died” |
| Residual unpaired, **correlated** (lock / swap / background mid-gap) | Singles cluster on user/infra events | **worse than none** — this is what rounds 3–5 closed |
| Residual **split pair** | Same-length frames across a TLS boundary, linkable | **worse than unpaired** — already ruled P1, closed for deliberate swaps |

“Cover marks exactly the sends that hit a transport failure; no cover marks nothing” confuses two observables. Under no cover, **every** send is marked as real activity. Under cover with rare uncorrelated unpaired frames, the residual singles are a subset of failure moments, and the successful pairs still do the job cover is for (equal-length framing on one connection). The rationale kills **correlated** intermittent cover, not cover with a nonzero physical residual.

Where the rationale *does* bite hard: if residuals are **inducible** (fill OkHttp queue; trip `sendLimit`), an adversary can force marks or real-send failures. That is a different kind of objection than “TLS can die in 5–50 ms.”

---

### 3. What residual rate makes cover net-negative? Is shipping without a number defensible?

**No single universal threshold, but the decision function is clear.**

Rough observer model (TLS sizes/timing only; real-first is invisible at that layer):

- Let \(p\) = fraction of real sends that leave the wire unpaired (or fail because of cover).
- Cover is **clearly net-positive** when \(p \approx 0\) and unpaired events are uncorrelated with sensitive actions.
- Cover is **net-negative or worthless** when:
  - \(p\) is high enough that the traffic is mostly singles again (feature ≈ off, with extra volume), or
  - unpaired / real-fail events are **adversary-inducible at will**, or
  - unpaired events are **tightly correlated** with lock, transport change, or other sensitive acts (the classes already fixed).

Nobody has measured \(p\). That is a real gap.

**Shipping without that number is defensible for a disclosed beta only if all of these hold:**

1. Requirements are rewritten so absolute claims are not false.
2. Residual classes are restricted to **unpaired real only** (never lone decoy, never split) — which the design already enforces on declared paths.
3. Correlated client-driven classes stay closed (they are).
4. `SECURITY_MODEL.md` / U6 copy name the residual classes without claiming absolute uniformity or absolute non-harm.
5. Known inducible volume effects (relay budget; ideally queue pressure) are tracked for mitigation (CX23(b) already exists for budget), not waved as “humans won’t hit 100/min” forever.

Shipping **while still claiming R-U3-1 / R-U3-3 absolute** is **not** defensible, measurement or not.

---

### 4. Material differences among the four mechanisms

**Yes — they are not one defect class.**

| # | Mechanism | Requirement hit | Inducible? | Correlated with user act? | Shape | Client-fixable in pairing? |
|---|---|---|---|---|---|---|
| 1 | Decoy fills OkHttp outbound queue → next real `send` false | **R-U3-1** (real fails because of cover) | under load / stalled writer | weak (pathological backlog) | real fail / cascade | only by dropping cover under backpressure (trades R-U3-3) |
| 2 | Worker blocked >250 ms → terminal fallback → admission refused | **R-U3-3** | only if build blocks that long at lock | **yes (vault lock)** | unpaired real only | no — alternative is hung lock without key wipe |
| 3 | 2× volume on shared `sendLimit` | **R-U3-1** | high-volume sender / low limit | no (volume) | real rejected (and today silent via empty `onServerError`) | no sound client headroom; **relay-side** |
| 4 | Socket dies in 5–50 ms gap | **R-U3-3** | only by killing the socket generally | no | unpaired real (split across reconnect is not the normal path: reconnect backoff ≥1 s ≫ gap) | no |

**Unpaired real vs split pair:** material. Split is stronger (cross-connection linkage + infrastructure correlation). Unpaired is lower-grade and often jitter/network-plausible. That distinction already justified the `quiesce` work.

**Uninducible vs inducible:** difference in kind. (4) is physics of two writes. (2) is a deliberate security trade (wipe beats framing). (1)/(3) are shared-resource costs that scale with load and can be weaponized or hit by bursty clients — same *shape* as inherent 2× volume, but not the same *threat*.

**R-U3-1 vs R-U3-3 residuals:** (1) and (3) harm the real send; (2) and (4) deliver the real and fail only the cover. Collapsing them all as “R-U3-3 marked frames” is how the dispute got muddled.

Position B’s claim that every residual is “unpaired real, never lone decoy, never split” is **true of (2) and (4) as implemented**, and is the right *shape* constraint for accepted residuals. It does **not** reclassify (1) and (3) as R-U3-3 framing residuals; those remain real-send harm.

---

### 5. Replacement wording

**R-U3-1 — Real-send supremacy (absolute where structurally enforceable).**

> A real send must not be blocked, failed, reordered, or made less durable by **cover-specific work ordered before the socket handoff**, and must not have its durability barrier or publish tail widened by cover. Within a pair, the real frame is always committed first so cover cannot take that pair’s last local enqueue slot from its own real frame.
>
> **Accepted residual (inherent 2× volume):** cover shares the account’s relay send budget and the client’s WebSocket outbound queue with real traffic. Cross-send preemption under load or rate limit is an accepted cost of pairing. Mitigations that are sound only on the relay (exempt/raise cover budget; attribute `rate_limited` to a message id) are tracked separately and are not client-pairing obligations. Cover must still never run *instead of* a real publish or in front of one.

**R-U3-3 — Failure uniformity for client-controlled paths; bounded physical residuals.**

> **Client-controlled** cover lifecycle (admission, drain, terminal stop, transport swap, build-then-admit, real-first) must not produce intermittent markable framing: no lone decoys, no pairs split across a deliberate TLS boundary, no cover dropped because teardown raced the register on a path the client owns.
>
> **Accepted residual class — unpaired real frames only**, and only from:
> 1. natural transport failure between the two writes (including death in the drawn gap);
> 2. terminal teardown’s bounded worker wait (`TERMINAL_TEARDOWN_WAIT_MS`) when the confined worker is blocked mid-build — preferred over a vault lock that never reaches key wipe;
> 3. cover construction failure after the real handoff (R-U3-4), treated as a builder defect to fix if reached in practice.
>
> These residuals must never appear as lone decoys or as split pairs. They need not be zero-rate; they must not be used as a licence for client-induced stutter. Persistent causes (no synthetic account, provisioning off) still yield **uniformly off** cover.

**Rationale amendment (replace “intermittent cover is worse than none” as absolute):**

> Intermittent cover that is **correlated with sensitive client events** or **adversary-inducible** is worse than no cover. Residual unpaired reals from uncorrelated transport failure at low rate are not.

---

**Bottom line on A vs B**

- **Position A pure** (“reachable absolute violation ⇒ P1 code defect / don’t ship”) **misclassifies** inherent transport costs as pairing defects and would freeze a feature whose fixable surface is largely clean.
- **Position B pure** (“declared residual satisfies absolute”) **launders a false claim** and would let any unfixable failure be promoted to residual by documentation.
- **Right ruling:** requirements change; residual class is real and mostly inherent; feature may ship under the rewritten bar with disclosure; residual *rate* and inducible load paths remain open product/relay work, not U3 re-litigation of confinement.

---

# 2. FINDINGS

None as new pairing/ordering P1s. The four agreed mechanisms are **requirement/spec conflicts under current absolute wording**, not newly reachable interleavings missed by rounds 1–5.

Secondary (prior rounds’ residual surface, not re-derived confinement bugs):

1. **P3** — `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:877` / `:962` vs `:1064–1089`  
   R-U3-1 and R-U3-3 are still labeled **Absolute** while §4.3 lists residuals that are reachable counterexamples (terminal fallback unpaired real; natural socket death mid-gap). Same contradiction as the round-6 dispute. Tests miss it because they assert the residual path (`DecoySendPairingTest.kt:1380`) rather than the absolute prose. **Disposition: rewrite, not code chase.**

2. **P3** — `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:1244–1245`  
   “`sendLimit` … a human sender will not approach it. Noted, no action” understates the R-U3-1 cross-send preemption already tracked as CX23(b) and leaves residual (3) softer in the design doc than in the pairing kdoc (`DecoySendPairing.kt:184–190`). Not a new mechanism; residual list incomplete relative to implementation honesty.

3. **P3** — OkHttp queue competition (mechanism 1) is argued in review and implied by `emit` → `WebSocket.send`, but is **not** listed beside the three residuals in §4.3. Same class as budget doubling (shared finite resource), different layer. No production test exercises backpressure; fakes are unbounded. Latent disclosure gap, not a new ordering hole.

---

# 3. MISSING CONTEXT

- **OkHttp `RealWebSocket` queue policy** (exact `MAX_QUEUE_SIZE` / close-on-full behavior in the resolved dependency): would refine severity of mechanism 1 (false return only vs socket cancel cascade). Defect class: R-U3-1 cross-send preemption under backpressure.
- **Measured residual rate** \(p\) on device under lock storms, flaky networks, and burst send: needed for the net-value half of the ship decision. Defect class: whether R-U3-3 residuals stay noise.
- **Deployed relay `sendLimit` configuration** if not the repo default 100/min: same shape as mechanism 3 at any finite limit; changes only how early humans hit it.

---

**VERDICT: FINDINGS (0 P1, 0 P2, 3 P3)**
