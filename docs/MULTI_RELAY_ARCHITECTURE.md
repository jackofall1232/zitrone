# Multi-Relay Architecture: Registry Abstraction, OPK-Claim Correctness, and the Path to Permissionless Relays

**Status:** Architecture decided. Implementation scoped to the relay-work branch (§11).
**Revision:** 3 — consolidated. Supersedes revisions 1 and 2 in full.
**Relates to:** `RELAY_FAILOVER_ARCHITECTURE.md`, `SECURITY_MODEL.md`, V2 Algorand backlog (IdentityRegistry, RelayRegistry, DeviceRegistry)

---

## 0. Why this document exists, and why now

Zitrone currently runs a single production relay. Every message depends on it. That is the single point of failure this architecture exists to remove.

This is **not** scope creep pulled forward. Relay addressing is load-bearing protocol logic — how a client finds and talks to a relay at all. Most features (attachments, dead-drop refinements, decoy tuning) are additive: an old client that doesn't know about them keeps working. Relay addressing is not additive. If V1.0.0 ships with clients hardcoded to one relay, every later move toward multi-relay requires either a flag-day migration (every user updates simultaneously or silently stops receiving messages) or maintaining the single-relay path forever as legacy. Both are unacceptable — same category of one-time, no-do-over decision as package ID / signing cert permanence on Play, or the sublemonable production-identity preservation on CX23.

**The active-passive failover model is explicitly rejected as the V1.0.0 target.** A primary relay with a warm standby is not a solution to single-point-of-failure — it is a chokepoint with a spare. It still concentrates all traffic on one box, still fails when two specific machines are unavailable, and does not scale: every capacity increase means promoting another standby rather than adding a peer. V1.0.0 ships **three active relays as equal peers**, any of which can serve any client, with the address book (§3) as the selection mechanism. This supersedes the active-passive framing in `RELAY_FAILOVER_ARCHITECTURE.md`.

The design goal is a system that is **functional and simple at three relays** and **scales to a permissionless network** without a client-breaking transition at any point along the way.

---

## 1. The organizing principle: known operators vs. unknown operators

This is the single most important idea in this document. Earlier drafts conflated it with relay *count*. **Relay count is not what changes the required trust model. Operator knowledge is.**

- **Known-operator relays** — run by HoboJoe, or by someone with a direct relationship who has been vetted (a friend adding a few servers). Whether that is 3 relays or 20, the honest assumption is **crash-fault only**: a relay may go down, run slow, or hit a bug, but it will not deliberately lie, double-vote, or attack the network.
- **Unknown-operator relays** — run by a stranger, admitted by software rules rather than by relationship. Here the assumption must become **Byzantine-fault**: the relay may actively misbehave — double-vote, censor selectively, or attempt traffic analysis on what passes through it.

**The base protocol (§2–§7) is designed to work correctly and identically at any relay count, provided every operator is known.** Adding a friend's three servers to HoboJoe's three is not a scale event requiring new machinery — it is the same protocol with more participants.

**The trigger for the permissionless machinery in §9 is not a relay-count milestone.** It is the first relay joining that nobody in the project has a relationship with. That is the moment crash-fault assumptions stop holding. Until then, §9 stays fully specified and deliberately unbuilt.

---

## 2. Two problems, two timescales — do not conflate them

| | Registry / Attestation | OPK-Claim Correctness |
|---|---|---|
| **Question answered** | "Which relays exist and are healthy?" | "Who may serve this specific one-time prekey, right now?" |
| **Timescale** | Hours | Milliseconds, per session establishment |
| **Backing store** | Server-hosted signed list → later Algorand-anchored | Off-chain, short-lived, relay-to-relay |
| **Public?** | Yes, by design — relay addresses must be discoverable | No — this is real-time activity metadata |
| **Failure cost** | Stale address book; client retries or falls back | Silent session-state corruption for one session |

Using the slow public mechanism (Algorand) to adjudicate the fast private one (OPK claims) was considered and rejected — see §6.

---

## 3. Layer 1: Registry abstraction — the V1.0.0 requirement

**Client contract, fixed from V1.0.0 onward:**

1. The client hardcodes no relay address, onion, or hostname.
2. It resolves relays through a registry abstraction and connects to what it is handed.
3. Its handshake assumes "a relay I was just given," not "the relay that has always existed."

**Registry resolution must not itself be a single point of failure.** A client querying one hardcoded registry URL has moved the single point of failure sideways, not removed it. The resolver checks multiple sources in order and uses the first that succeeds:

- Signed bootstrap snapshot embedded at build time (works with no network reachability to anything else)
- Primary registry endpoint (clearnet)
- Registry mirror over Tor
- Registry mirror over I2P
- Last-known-good cached snapshot on device
- *(future)* Algorand-backed resolver, once §7 is live

**Registry responses are signed, versioned manifests — not bare lists.** Minimum contents: schema version, epoch, validity window, relay list, hash of the previous manifest (rollback detection), signatures. **The client verifies the manifest signature, not merely the TLS connection to whichever endpoint answered.** This buys key rotation, rollback detection, mirror independence, and offline operation, and it is the reason this is worth doing properly at V1.0.0 rather than retrofitting.

**Initial implementation is deliberately boring:** a signed, server-hosted list returning the current relay set. No quorum, no Algorand, no rotation needed for this layer to be correct. The abstraction and the signing are the point.

**What this unlocks with no future client update:** migrating the registry's backing store to Algorand-anchored data; adding or removing relays freely; relay prioritization by speed and consistency as a registry-side ordering change.

---

## 4. Layer 2: OPK-claim correctness

### 4.1 The problem

X3DH one-time prekeys must be issued to exactly one sender, once, then consumed. Identity keys and signed prekeys are intentionally reusable; the OPK is not. With multiple relays independently serving prekey bundles, two relays can both see an OPK as unclaimed and issue it twice unless the claim mechanism is genuinely safe under concurrency.

### 4.2 Threat model — honestly scoped

- **Not a confidentiality attack.** Winning a race grants no access to any other session's content; X3DH/Double Ratchet isolation holds regardless.
- **Not a practical DoS vector.** Deliberate exploitation requires either knowing the near-exact instant a real session is being established (no such visibility exists) or sustained volume racing — which is a rate-limiting problem, not a consensus problem.
- **No monetary motive.** Nothing is stolen or gained by winning. A profit-seeking attacker has weak reason to spend resources here; the realistic adversary with actual motive seeks surveillance capability and is not cost-constrained, which is relevant to §9 but not to this section.
- **The real risk is reliability.** Ordinary jitter or retry logic can trigger this with no adversary at all, and the failure is silent and hard to diagnose.

**Conclusion: this is a correctness property, not an anti-attacker defense.** Design accordingly — solid, not over-engineered.

### 4.3 Why "majority acknowledgment" alone is insufficient

An earlier draft specified: claim locally, broadcast, proceed on majority acknowledgment. **This is not safe on its own.** Two claims can each gather a majority if the relay in the overlap does not durably and atomically bind itself to one of them. Five relays: Claim A collects R1, R2, R3; Claim B collects R3, R4, R5. They overlap at R3 — but that only helps if R3 atomically recorded its vote for A, refuses B, and cannot forget across a restart. "A majority responded" is not the safety property. "The overlapping voter cannot vote twice" is.

### 4.4 The claim protocol

```
claim_key = recipient_device_id || opk_id
claim_id  = random_128_bit_id
```

Per-relay atomic state, one of: `UNCLAIMED` / `CLAIMED(claim_id, claimant_relay, expires_at)` / `CONSUMED(session_init_hash)`.

Minimum safe acknowledgment (illustrative; the atomicity guarantee is the requirement, not the exact schema):

```sql
INSERT INTO opk_claims (
    recipient_device_id, opk_id, claim_id, claimant_relay, expires_at
)
VALUES (...)
ON CONFLICT (recipient_device_id, opk_id) DO NOTHING
RETURNING claim_id;
```

A relay acknowledges **only if** it inserted the claim, or the stored claim carries an identical `claim_id`. It must refuse a conflicting `claim_id` for the same key. That — not "I received your broadcast" — is what an acknowledgment means.

**Claim state must be locally durable for the claim lifetime.** A relay that acknowledges Claim A, restarts, forgets, and then acknowledges conflicting Claim B allows two majorities to form. This is protocol state, not logging, and it does not conflict with §6's zero-trust rule: records hold opaque IDs only — no sender identity, no IP, no content — and are retained only as long as needed:

```
retention = max(initial-message acceptance window, retry window, signed-prekey overlap window)
→ then securely deleted
```

**Majority quorum** applies on top of this: proceed once `floor(N/2) + 1` relays have acknowledged in the strict sense above. Majority rather than unanimity ensures one slow or unreachable relay cannot stall the network. Any two majorities of N overlap by at least one node — and that overlap provides safety *only because* §4.4's atomic mechanism prevents the overlapping node from voting twice.

**Relay-set versioning:** quorum is evaluated against an explicit relay-set epoch tied to the registry manifest (§3), so all participants agree what "majority" means as N changes. A vote taken against a stale membership view is not safe to count.

### 4.5 Commit before release

An earlier draft had the relay claim locally, attempt quorum, then decide. If the sender already holds the OPK when quorum fails, there is no way to walk it back.

**Correct sequence:**

1. Client requests a prekey bundle
2. Relay selects a candidate OPK
3. Relay seeks quorum for that specific candidate claim
4. **Quorum succeeds** → commit the claim, return the bundle **with** the OPK
5. **Quorum times out or fails** → abort/tombstone the tentative claim, return the bundle **without** an OPK

The sender never holds an OPK whose claim is uncommitted.

### 4.6 Graceful degradation, never hard failure

On quorum timeout the relay falls back to X3DH's own defined path: a bundle without a one-time prekey, carrying slightly reduced forward-secrecy contribution for that one session. Never a crash, never corruption of other sessions, no new machinery required.

### 4.7 Rate limiting

Per-identity-key rate limiting on OPK-fetch remains standing regardless of the above — cheap, standard, and it closes the only realistic volume-based path independent of quorum correctness.

---

## 5. Layer 3: Multi-hop routing — current status and remaining work

**Status: plumbed, not wired.** This is documented accurately in `SECURITY_MODEL.md` and must stay that way until it changes.

**What already exists in the codebase:**

- `packages/crypto/src/onion.ts` — full layered onion encryption. One libsodium sealed-box layer per hop; each relay peels exactly one; framing marks the innermost layer; sealed boxes are anonymous, so a relay learns nothing about who wrapped its layer.
- `packages/relay-client/src/circuit.ts` — circuit construction and path selection. **AS diversity is a hard constraint** (no two hops in the same autonomous system); geographic diversity preferred as a tiebreak; guard (first hop) pinned and rotated far less often; rotation at 10 minutes or 100 messages; only nodes advertising `multi_hop` are eligible. Returns `null` when no diverse path of the requested length exists.
- `server/internal/api/relay.go` — `POST /relay/forward`. Peels one layer, forwards to the next hop, stores the envelope at the innermost layer. Previous hop never retained. **SSRF guard:** next hops must be on an operator-configured allowlist, and with no allowlist the relay forwards nowhere (fail-closed).

**What remains:** wiring the ordinary client send path through `buildOnion` + circuit selection, and defining fallback behavior when `selectPath` returns `null` or a hop fails.

### 5.1 Failure behavior — the design pass this needs

Established anonymity networks were checked rather than guessed at, and both converge on the same answer:

- **Tor** tears the whole circuit down on unrecoverable hop failure (DESTROY away from the client, DESTROY or RELAY_TRUNCATED toward it) and does not reuse truncated circuits. Its resilience comes from maintaining many pre-built circuits and switching, not from repairing or degrading one.
- **I2P** requires every hop to agree before a tunnel is enabled at all, tests tunnels continuously, and marks a failing tunnel unusable and rebuilds rather than patching it. I2P's own documentation notes that longer tunnels have a higher build-failure rate and that beyond ~3 hops anonymity does not meaningfully improve — which is why 2–3 hops is its practical default.

**Neither answer transfers to a 3-relay network,** because both depend on a pool large enough that "just build another circuit" is cheap. At N=3 there are no spares.

**Decisions that follow:**

- **Handshake-first construction.** All hops confirm before any message is handed off, matching both Tor and I2P. This removes mid-transmission ambiguity for the bulk of the transfer; what remains is a narrow window where a relay dies between confirming and forwarding.
- **Degrade-to-fewer-hops as the low-relay-count fallback,** with the ladder (3→2→1) an explicit decision at implementation time — not silent, and disclosed in `SECURITY_MODEL.md`. Falling back to a single hop means that relay sees both sender and recipient for that message, which is exactly the property multi-hop exists to provide. This is an availability-over-privacy tradeoff and must be documented as one, never implied away.
- **Graduate to rebuild-from-spares** once the relay pool is large enough for a fresh diverse path to be reliably available — at which point Tor/I2P's model becomes available and degradation stops being necessary.

### 5.2 What multi-hop is actually for

For a user connected through Orbot, Tor already provides network-layer multi-hop before traffic reaches any Zitrone relay. Zitrone's own onion routing is a second, independent layer at the application layer.

**The primary value is therefore clearnet protection** — users who, for whatever reason, are not running Tor or I2P, for whom Zitrone's own routing is the only thing preventing one relay from seeing both ends. For Tor/I2P users it is genuine defense-in-depth (an application-layer compromise does not automatically expose what the transport layer hides), but the marginal gain is smaller. `SECURITY_MODEL.md` should say this plainly rather than implying Zitrone's multi-hop is the sole source of a guarantee Tor already provides.

### 5.3 Transport preference: Tor primary

Real-world testing: Orbot/Tor reaches usable connectivity in a minute or two; I2P can take minutes to hours after startup before tunnels carry data reliably. This matches each system's architecture — Tor bootstraps against a directory-authority consensus of profiled relays, while I2P must discover peers and build profiles before its short-lived (10-minute) tunnels perform.

**Decided ordering:** Tor when Orbot is connected → I2P when tunnels are *actually built and passing data* → clearnet with the visible warning indicator. The "actually passing data" check matters specifically: an I2P router that is running but has not yet built viable tunnels will black-hole messages if the client treats "installed and running" as "usable."

---

## 6. Explicitly rejected: Algorand in the OPK-claim path

- **Latency mismatch** — block finality is seconds; session establishment should feel instant.
- **Cost mismatch** — a fee and a permanent record per new conversation.
- **Zero-trust violation (hard constraint)** — no message-related or identity-correlatable data goes on any blockchain, including claim metadata tied to a real identity. That would be a metadata leak undercutting the Tor/I2P/decoy investment elsewhere.
- **Wrong trust model** — Algorand consensus serves large sets of mutually distrusting strangers. The OPK-claim problem, at known-operator scale, is a small set of relays that already trust each other not to be malicious.

---

## 7. Layer 4: Registry attestation on Algorand

**On-chain, and only this:** periodic (daily or 12-hourly) batched attestation of which relays are registered and their reachability over the window; relay addresses and onions (already public by necessity); later, aggregate speed/consistency stats for prioritization.

**Never on-chain:** message content or metadata; any per-session or per-message event; anything correlatable to a user identity or message.

**Honest oracle language.** The chain knows only what was submitted to it. Unless an independent observation process is defined — who probes, how many probes, from which networks, how false negatives are handled, whether self-reporting counts, how witness Sybils are resisted — the honest description is **"Algorand-anchored relay observations,"** not "Algorand-verified uptime." Documentation must match whatever is actually implemented.

### 7.1 Overlapping leases, not synchronized cliffs

A cycle where every relay expires at the same boundary creates a network-wide cliff: one outage in the chain, the probing mechanism, or a software bug could delist a large fraction of the network simultaneously.

```
proof/attestation interval:  6 hours
registry lease:              8 hours
publish window:              final 2 hours before renewal
```

A relay disappears when its lease actually expires, not when a global cycle ticks. Fail-closed is preserved exactly: **no valid unexpired lease = not returned to clients.** This is a scheduling fix, not a weakening.

---

## 8. Bootstrap topology (what to actually stand up)

**Current production relay set:** CX23 (Hetzner) and CX-IS (1984 Hosting, Iceland). **CX33 is a build box and performs no relay work, ever.**

**Target for V1.0.0: three active relays, equal peers.** Not a primary with standbys. Each is listed in the address book, each serves clients identically, and a client picks a working one. Losing any single relay degrades capacity, not availability.

| Relay | Provider | Role |
|---|---|---|
| CX23 | Hetzner (Helsinki or Germany) | Active relay |
| CX-IS | 1984 Hosting (Iceland) | Active relay |
| *new* | TBD | Active relay |

**On AS diversity and the third relay.** `selectPath` enforces distinct autonomous systems as a hard constraint, but **only for multi-hop circuit construction** — a random pick from the address book has no such rule. Since triple-hop wiring is deferred past V1.0.0 (§11), relay #3 does *not* need a third AS to satisfy V1.0.0. Provider diversity remains worth having where it is cheap — it is genuine protection against a single provider's outage or account action, and it becomes a hard requirement the moment circuits are wired — but it does not gate standing up the third relay now.

**AWS sizing note (if AWS is used):** a relay-only box (Go binary + Postgres + Tor) fits t3.micro with Postgres tuned down and swap enabled; t3.small removes the tuning work at roughly $15/month against credits. Start on micro — resizing is a stop/change-type/start operation with no rebuild. If the stack runs comfortably in 1GB, that is direct evidence the opportunistic desktop-node tier (§9.5) is realistic. Watch egress specifically: AWS bills data out, and a relay is by definition a data-out machine — fine for a six-month test window, wrong economics for a permanent node compared to flat-bandwidth providers. **Do not co-locate an I2P transit-contribution router on a metered-egress box** (see ops note on network contribution — that workload belongs on flat-bandwidth hosting, on its own instance, never sharing a box with a relay).

**Credentials:** a new relay means new SSH keys, relay identity key, and Tor hidden-service keys. Add them to the credentials inventory at provision time, not after — short-lived test boxes are exactly where keys get lost.

**Honest framing (do not oversell in release notes or `SECURITY_MODEL.md`):** this is meaningful redundancy against *infrastructure* failure. It is not decentralization against *operator* risk while every relay traces back to one person. State the permissionless architecture as the target and the current reality as the current reality.

---

## 9. Permissionless admission — specified, deliberately unbuilt

**Build trigger (§1):** a relay operator with no relationship to the project attempts to join. Growth from 3 to 20 relays among known operators does **not** trigger this section.

### 9.1 PoW admission

Every relay solves a proof-of-work puzzle each time it publishes to the address book, on the attestation cycle (§7.1) — not a one-time fee. A one-time cost is a price an attacker pays once and amortizes forever; a recurring cost makes a Sybil fleet expensive indefinitely.

**Binding:** the proof must be bound to the relay's identity (address/onion/pubkey as puzzle input), so one solved proof cannot be replayed across many claimed identities.

**Fail-closed, no grace period:** a relay without a valid proof is not written to the address book for that cycle, re-entering only on a subsequent successful proof (subject to §7.1's lease overlap for scheduling, which is not an exception to fail-closed). No special-casing for *why* a proof is missing.

**Algorithm: Argon2id.** Memory-hard, resistant to the ASIC advantage that would price out modest hardware and defeat the accessibility goal, and it reuses a dependency already justified and in production (vault KDF, Argon2id p=1 via lazysodium). Relay-admission parameters are a **separate tuning question** from the vault's — different goals (offline brute-force resistance vs. a completable periodic cost) — and must not inherit the vault's values by default.

**Damped difficulty retarget.** Difficulty moves a fraction of the gap toward the target each cycle rather than jumping. A single-step retarget lets an attacker flood one window with fast solves and lock in an inflated difficulty that prices out honest hardware immediately; damping requires sustained pressure across many cycles and relaxes as soon as pressure stops. Exact damping factor and averaging window are empirical, tuned against real solve-time data.

**What PoW does and does not do:** it raises the ongoing cost of running many relays. It does not make an admitted relay behave honestly — which is precisely why it is insufficient on its own for quorum participation (§9.2).

### 9.2 Role separation

Once operators are unknown, §4's crash-fault assumption fails. PoW proves compute was spent, not that a relay will vote honestly, refuse conflicting claims, avoid censoring, or decline traffic analysis. Majority quorum tolerates crashes; it does not automatically tolerate malicious voters.

- **Routing relay** — permissionless, PoW-admitted. Forwards opaque packets, advertises transport endpoints, participates in path construction. **Cannot** vote on OPK ownership or publish authoritative registry state.
- **Storage relay** — stronger requirements; may hold encrypted envelopes and replicated prekey material, subject to conformance checks.
- **Prekey authority** — eligible for OPK-claim quorum. At known-operator scale this is simply every registry relay. Once unknown operators exist it requires a vetted subset, the rotating pool (§9.3), or both.

This split need not be enforced before the §1 trigger; until then every relay is trivially eligible for every role.

### 9.3 Rotating validator pipeline (deferred)

Three pools exist concurrently, each 10 minutes ahead of the next in a 30-minute lifecycle:

- **Serving** — handling live OPK-claim quorum votes
- **Proving** — generating/finalizing eligibility proofs, taking over next
- **Selecting** — being chosen from current eligibility data

Every 10 minutes each advances: serving retires, proving becomes serving, selecting becomes proving, a fresh selection begins. Every pool gets 20 minutes of lead time before serving live traffic, which is what makes expensive proof generation tractable without stalling anything.

**Handoff rule:** the retiring pool stops accepting *new* claims at the tick and finishes in-flight work; the incoming pool — already selected and proven — starts fresh immediately. No mid-claim ambiguity, because no pool is ever eligible to receive a claim before its proof is complete.

**Minimum viable size.** This requires enough relays to staff three distinct pools concurrently — roughly 10–15+ before it functions as designed. Below that it degenerates: there is nothing to select *from* beyond who is already serving. This is a further reason the machinery is gated rather than built early.

**Open:** whether "selecting" means fresh PoW each cycle or lightweight selection among already-qualified candidates. A real cost/security tradeoff, decided against real data.

### 9.4 zk-proof admission — future direction

**Idea:** rather than trusting a witness committee to verify Argon2id off-chain, the relay produces a succinct proof that it performed the computation correctly, verified directly.

**Why not first:** memory-hard functions are deliberately expensive to represent in a zk circuit — the same property that resists ASICs resists efficient proving. This is active research territory, not a drop-in library. Committing it to a launch critical path is an open-ended research dependency.

**What ships when permissionless admission is first built:** an off-chain witness model. Independent verifiers check the Argon2id proof and sign an attestation (`relay_pubkey, cycle_id, proof_hash, difficulty, expiry`); the registry accepts an *m-of-n* threshold. **This is federated trust, not trustlessness, and documentation must say so** — "verified by an m-of-n witness set," never "verified by the blockchain."

**Migration:** §9.3's pipeline is agnostic to which verification method is used. Adopting it does not foreclose zk later; that is a swap of one step, not a redesign.

### 9.5 Opportunistic desktop relays

Once the Linux desktop client exists, any machine running it could serve as a **routing relay** while online. This fits the routing tier well precisely because stateless forwarding tolerates churn — a relay that vanishes when a laptop closes is a much smaller problem for a forwarder than for a prekey authority holding durable claim state.

The existing fail-closed lease design already handles this without special-casing: prove in when online, drop out when offline. What needs deliberate design is treating **churn as the normal case rather than the exception** — path construction should avoid depending on intermittent nodes as load-bearing hops when steadier relays are available, and anything requiring persistence should prefer stable relays. Dependent on the desktop client shipping; worth building because it is the cheapest possible path to a large routing tier.

### 9.6 Stake — considered, not adopted

**Version A (externally-acquired stake)** raises a capital barrier and quietly narrows "everyday person with a spare box" to "everyday person with spare capital." Not recommended.

**Version B (self-mined stake)** — a relay bootstraps via pure PoW, solving mints stake, and spent stake reduces the next cycle's cost. No capital barrier; established honest relays get cheaper puzzles as a reward for participation rather than for wealth. It also carries a useful Sybil property: fresh fake relays get no discount, so a new Sybil fleet is strictly more expensive per relay than a few long-lived ones.

**Neither ships initially.** PoW alone is fully specified and sufficient. Revisit Version B only if real Sybil pressure demonstrates a need, and tune it against real participation data rather than guesses. Open if pursued: the discount mechanic, whether stake decays or resets on a missed cycle (reset is simpler and consistent with fail-closed), and whether stake is transferable (it must not be — transferability reintroduces the Version A capital barrier through a side door).

---

## 10. Transition: known-operator → permissionless

When the §1 trigger fires, the switchover is a **relay-operator coordination event, not a client flag day.**

- **Clients are unaffected.** The registry abstraction (§3) already guarantees clients neither know nor care how many relays exist or what tier they occupy. Confirm at implementation time that §9's protocol additions fit the existing wire format; do not assume it.
- **Relay operators must update.** A relay that does not speak the current protocol should simply fall out of eligibility rather than half-participate — consistent with fail-closed everywhere else, not a special migration path.
- **Practically:** an announced mandatory relay-software update with a stated cutover, aimed at a small, technically sophisticated, directly reachable population. This is precisely the flag day §3 exists to keep away from end users.

---

## 11. Summary: what ships when

**V1.0.0 — required, client-visible, irreversible:**
- Signed, multi-source registry resolution. No hardcoded relay addresses. Manifest signature verified by the client.
- **Three active relays, all equal peers in the address book.** Not primary-plus-failover. A client selects a working relay from the registry; every relay serves identically. This is a deliberate rejection of the active-passive model: a primary with a warm standby is still a chokepoint, still a single point of failure with extra steps, and does not scale — the failure mode is simply deferred to "both boxes down" rather than removed.
- Atomic durable OPK-claim protocol (§4.4), commit-before-release (§4.5), graceful degradation (§4.6) — operating across the three active relays
- Transport preference: Tor primary, I2P on verified tunnel readiness, clearnet with warning (§5.3)

**Post-V1.0.0 — additive, no client-breaking change:**
- Wire the existing onion/circuit code into the client send path, with the fallback ladder decided and disclosed (§5.1). Plumbed already; wiring is a later update.
- **AS diversity becomes a hard requirement at this point, not before.** `selectPath` enforces distinct autonomous systems only for multi-hop circuit construction — random relay selection from the address book has no such constraint. Relays added before triple-hop is wired need not be on distinct ASes; relays intended to participate in circuits must be. Aim for provider diversity where it is free, but it does not gate V1.0.0.
- Registry backing store toward Algorand-anchored attestation with overlapping leases (§7)

**Permissionless phase — gated on §1's trigger, specified in advance:**
- Role separation; PoW/Argon2id admission with damped retarget; rotating validator pipeline; witness-verified proofs now, zk later; opportunistic desktop routing relays

**Hard constraints — not revisable without deliberate re-scope:**
- No message content, identity-correlatable data, or per-session events on any blockchain
- Fail-closed everywhere; graceful degradation, never hard failure, on quorum timeout
- Majority only on the hot path — never "all relays"
- Documentation claims match the actual trust mechanism in use. Nothing is claimed before it ships and is verified.
