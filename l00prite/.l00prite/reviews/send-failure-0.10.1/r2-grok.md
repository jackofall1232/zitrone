I'll review the full send-failure surfacing unit read-only: protocol context first, then the listed Android, relay, and test files against the round-1 fixes and the new send-timeout surface.I'll leave the other reviewer's round-2 file unread and inspect the unit sources and tests directly.Checking one more interaction: whether an in-flight first attempt is cancelled on retry, and confirming exact line numbers for findings.# FINDINGS

### F1 — P1
**Where:** `MessageRepository.scheduleSendTimeout` / `addOutgoing` (`MessageRepository.kt:61–64`, `433–447`); armed from `deliverAttachment` **before** blob upload (`MessagingCoordinator.kt:1327–1355`); retry under same id with no in-flight cancel (`MessagingCoordinator.kt:1418–1453`); relay uniqueness (`server/internal/db/schema.sql:41–44`, `store.go:167–170`).

**Sequence (concrete, reachable):**
1. User sends a large attachment (cap **8 MiB** plaintext ≈ ~11 MiB base64 on the wire) over a slow Tor/I2P path.
2. `deliverAttachment` encrypts, then `addOutgoing` → arms the **90 s** send timeout **while the message is still SENDING and the blob has not been uploaded** (`1327–1355`).
3. Upload takes **> 90 s** (common on a congested mobile Tor circuit at ~50–100 KiB/s; OkHttp `writeTimeout` is per-write, not whole-body, so a slow continuous upload is not cut off at 20 s — `CertificatePinning.kt:73–76`).
4. Timeout CAS fires: SENDING → **FAILED**. Bubble shows `!` / retry while attempt #1 is still uploading.
5. User taps retry. `retryable` flips FAILED → SENDING and starts `deliverAttachment` again under the **same** id. Attempt #1 is **not** cancelled; both run on `confined` and interleave at upload suspension points.
6. Attempt #1 finishes, `publishOutgoing` stores the envelope, `message.stored` → `markSent` **heals** to SENT; online peer gets `message.deliver` and **acks** (row deleted).
7. Attempt #2 then `StoreEnvelope`s the **same UUID** successfully (PRIMARY KEY free after ack), peer receives a **second** envelope (new ratchet ciphertext, same `id`).

**Wrong outcome:** User is first shown a **false FAILED** for an in-flight send; acting on it can **double-deliver**. Healing makes the late first receipt look healthy while the second send is still in flight — the “early fire is self-correcting” claim only holds if the user does **not** retry during the false FAILED window.

**Why tests miss it:** `MessageRepositoryTest` only advances virtual time after bare `addOutgoing` (no upload phase, no concurrent `deliverAttachment`, no retry-while-in-flight). No `MessagingCoordinator` test constructs the attachment path. Relay uniqueness is untested against “first acked, second re-inserts same id.”

---

### F2 — P3
**Where:** `MessageRepository.kt:76–81` (`markSent` kdoc), `116–119` (`markDelivered` kdoc).

**Sequence:** Read the public kdocs after the healing fix. Bodies accept `FAILED` (`93–95`, `127–130`); tests explicitly heal (`MessageRepositoryTest` “a real receipt heals…”, “a late relay receipt heals…”).

**Wrong outcome:** Docs still claim receipts “can never resurrect a … **FAILED** message.” That is the opposite of the intentional round‑1 fix. A later edit that “restores monotonicity” from the kdoc would reintroduce the P1 latch.

**Why tests miss it:** Behaviour is tested; kdocs are not.

---

### F3 — P3
**Where:** Client claims that the send budget runs **before** envelope parse so `rate_limited` often has no id — e.g. `MessageRepository.kt:416–418`, `MessagingCoordinator.kt:2349–2351`, `WsClient.kt:132–135`, tripwire prose in `DecoySendPairingTest.kt:1541–1542`. **Relay reality:** `hub.go:158–187` unmarshals the header **first**, then rate-limits, and echoes `msgID` on `rate_limited` when the id is a well-formed UUID.

**Sequence:** Read client comments vs `handleSend`. A normal client send that is rate-limited **does** get `message_id` today (`hub_test.go` rate-limit attribution test).

**Wrong outcome:** Maintainers are steered toward the pre-merge contract. The timeout is still justified (lost acks, older relays, malformed frames), but the stated “frequent unattributable `rate_limited`” rationale is **false against the merged relay**. Unattributable `rate_limited` remains only when parse/`uuid.Parse` fails (`msgID == ""` + `omitempty`).

**Why tests miss it:** Client unit tests never assert against live `hub.go` ordering; tripwires pin client source shape, not relay behaviour.

---

# CONFIRM-OR-REFUTE

### Round‑1 fix 1 — `markFailedByRelay` (SENDING-only) + receipt healing
**Mostly upheld; healing direction is the right tradeoff under a conceded relay.**

| Attack | Ruling |
|---|---|
| Stale/hostile error after SENT | **Blocked** — `markFailedByRelay` is SENDING-only (`188–194`). |
| Heal burned/removed | **Safe** — precondition fails on BURNING; missing id no-ops (`501–516` test). |
| Heal FAILED → SENT/DELIVERED | **Intended** — receipt beats a false/local failure. Relay can still lie (threat model). |
| `retryable` × heal | **Unsafe only when a prior attempt is still in flight** — see **F1**. Pure “error then late stored, no retry” heals cleanly. |
| `retryable` after heal to SENT | **Safe** — `retryable` requires FAILED. |

`markFailed` (local) still accepts SENT (`165`) — intentional for device-observed failure; cover-throw residual into that path is pre-existing and outside this unit’s new surface.

### Round‑1 fix 2 — null id / send timeout
**Fixes the unattributable hang for post-handoff waits; broken for attachment pre-handoff work (F1).**

### Round‑1 fix 3 — ownership comments
**Now accurate.** No `isMine` check; production graph: `addIncoming` forces DELIVERED; `markFailed*` exclude that. Type still allows `addOutgoing(isMine=false)` — comments admit that. No production path found that puts foreign mail into SENDING/SENT.

### Round‑1 fix 4 — tripwire + no `return` before yield
**Sufficient against the early-return defeat.** Pins yield first, then attribution, and rejects `return` before the yield. Mild over-constraint on future early exits in `onServerError`; not a defect. Still not a behavioural proof of wiring (see harness).

### Send-timeout design claims

| Claim | Ruling |
|---|---|
| Times relay **receipt**, not delivery | **Yes** for text-shaped paths: cancel on `markSent`/`markDelivered`/`markFailed*`; timer CAS is SENDING-only; `markDelivered` from SENDING also cancels (`123–137`). Once SENT, multi-day offline peer does not fail. |
| Needs no relay cooperation | **Yes** — local timer; cannot be starved by omitting errors (only delayed by never leaving SENDING, which is the bug it fixes). |
| 90 s safe for slowest transport | **For WS `message.stored` after handoff, plausible.** **For attachment upload before handoff, not** — see F1. Claim text talks circuit setup, not 8 MiB bulk. |
| Early fire self-corrects via heal | **Yes if user does not retry during false FAILED.** **No if they do while attempt #1 still runs (F1).** |
| Leaks / lifecycle | Cancel on mark*/burn/remove; re-arm on `retryable`; CAS prevents double-apply after leave-SENDING. Vault lock cancels session scope (jobs die). `clearAll` does **not** cancel `sendTimeoutJobs` (`379–387`) — usually masked by scope cancel; residual map/job hygiene only. Same-id reuse is only via `retryable` (re-arms correctly). No fire against a different message. |

### Declared cancel + CAS redundancy
**Argument is correct; not an `isMine`-style dead guard.**

- Cancel is the common path; under concurrency a job can be **past `delay` and inside synchronous `update`** when cancel runs — cooperative cancel may not stop that CAS.
- SENDING-only CAS is then the last line.
- Single-threaded virtual-time tests cannot discriminate either guard alone; that is a test-clock limit, not proof of unreachability.
- Keep both; do not promote either alone to “the” correctness proof without a concurrent test.

### UUID coupling (client mint ↔ relay echo ↔ `update` exact match)
**Holds for every id the Android client mints today.**

- Client: `UUID.randomUUID().toString()` → lowercase RFC‑4122.
- Errors: `uuid.Parse(header.ID).String()` → same canonical form (`hub.go:178–181`).
- `message.stored`: raw `header.ID` (`hub.go:210`) — same string the client put in the envelope and in `Message.id`.
- Match is exact string equality in `update` (`513–517`).

If a non-canonical id were ever used, error attribution could miss while `message.stored` still matched raw — **not reachable on the current mint path.** Silent miss → timeout → FAILED (bounded, not infinite SENDING).

### R‑U3‑1
**Not weakened.**

- Cover yield is still on `code == rate_limited` **before** and **independent of** id (`MessagingCoordinator.kt:2343–2371`); tripwire pins that.
- Timeout is local UI/state; it does not block, delay, or drop the real publish.
- A retry is a real send (and is how F1 double-delivers — a product bug, not a cover-traffic regression).
- Synthetic socket still ignores `messageId` on cover errors (`WsSyntheticSocket.kt:72–80`).

---

# HARNESS RULING

**Acceptable residual for merge of this change — not a merge blocker — with a named follow-up.**

**Why not a blocker now:**
1. Dangerous behaviour lives in `MessageRepository` CAS/timeout and `WsClient` normalisation — both **behaviourally** tested.
2. Coordinator glue is two straight-line statements whose **order and form** are pinned by a tripwire that already defeated the “early return” cheat.
3. Full `MessagingCoordinator` construction is Robolectric-scale for reasons orthogonal to this unit.

**Why still owed:** a tripwire cannot catch “listener never installed,” a silent rename of the seam, or a behavioural regression that keeps the same source substrings.

**Cheaper seam than a full app harness:** extract a pure/package-visible function or tiny type, e.g.

```kotlin
fun handleServerError(
  code: String,
  messageId: String?,
  onRateLimited: () -> Unit,
  markFailedByRelay: (String) -> Unit,
)
```

Unit-test: `rate_limited`+null id still yields; id present still marks; yield not nested under id; non-rate-limited never yields. Wire `MessagingCoordinator.onServerError` as a one-line delegate. No `Context` / Signal / notifications required.

---

# MISSING CONTEXT

- **Live Tor/I2P upload timing** for max-size blobs (would tighten F1 from “reachable by throughput math” to measured).
- **Whether product UX will show retry during long attachment SENDING** in a way that makes step 5 of F1 likely (assumed yes: FAILED is the only retry affordance).
- **OkHttp whole-request behaviour** on specific Tor builds (assumed continuous writes keep `writeTimeout` from killing multi-minute uploads).
- Did not re-run the suite (read-only review).
- Did not read the other blind reviewer’s round‑2 output.

**Checked:** full `MessageRepository` timeout/fail/heal/retry/burn paths; `MessagingCoordinator` send/retry/`onServerError`/publish; `WsClient` error dispatch; synthetic socket; decoy kdoc + tripwire; merged `hub.go` `handleSend` + store PK; attachment size cap and HTTP timeouts; R‑U3‑1 yield ordering.

---

**VERDICT: FINDINGS (1 P1, 0 P2, 2 P3)**
