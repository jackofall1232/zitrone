# Adversarial review — Zitrone 0.10.1, send-failure surfacing, round 1

**READ-ONLY.** Do not modify, create or delete any file. Do not run mutating commands. Report only.

You are one of two reviewers working **blind to each other**. Do not assume anything has already
been found. Repo root: `/root/zitrone`. Branch: `feat/0.10.1-send-failure-surfacing`.

## Review the WHOLE UNIT, not the diff

A prior release shipped a real defect because review was scoped to a fix diff and the original unit
went unexamined. Read this as a complete change, including the code it merely touches.

## What Zitrone is

A zero-knowledge, plausible-deniability encrypted messenger. **The relay is CONCEDED in the threat
model** — it sees cleartext `sender_id`/`recipient_id` on every envelope and can drop, delay, or
lie. Cover traffic (0.10.0) defends against a *network observer*, never against the relay. The
Android client is the security reference implementation.

## The defect being fixed

`onServerError` surfaced nothing. Every server rejection of a send was silently swallowed, so a
rate-limited or otherwise-rejected message stayed displayed as **`SENDING` forever** — not marked
failed, not retried, no error shown. Users had no way to know a send failed. This predates decoy
traffic.

It could not be fixed client-side before now: the relay's budget check runs before the envelope is
parsed, so `rate_limited` did not carry — and could not carry — a message id.

## The relay half (ALREADY DEPLOYED, and NOT IN THIS REPO)

⚠️ **Important for your review:** the relay-side change is deployed on the production box as commit
`1c63e8c`, which **has not been pushed to origin and is not in this repo.** You cannot read it.
What is source-verifiable here is the wire struct it populates:
`server/internal/ws/hub.go:126` → `MessageID string \`json:"message_id,omitempty"\``.

Claimed relay behaviour (treat as CLAIM, not fact): `message_id` is populated on `rate_limited`
(when the header parsed), `store_failed`, and `bad_envelope` (when the id is a well-formed UUID);
echoed only for well-formed UUIDs; `rate_limited` takes precedence over `bad_envelope`.

**A question worth attacking: does the client behave safely if that claim is false in any
direction** — field absent, field always empty, arbitrary or hostile ids, ids belonging to another
conversation, or the relay half reverted entirely by a redeploy from `main`?

## The change

- `net/WsClient.kt` — `Listener.onServerError` gained a third parameter `messageId: String?`; the
  dispatch reads `frame.optString("message_id").takeIf { it.isNotEmpty() }`, normalising
  absent/empty to null at the wire boundary.
- `MessagingCoordinator.kt` — the cover-traffic yield stays first and unconditional on the id;
  then `if (messageId != null) messages.markFailed(messageId)`.
- `decoy/WsSyntheticSocket.kt` — accepts and deliberately ignores the id; `rate_limited` routing to
  `CoverPressure` unchanged.
- `data/MessageRepository.kt` — **comment only, behaviour byte-identical.** An `isMine` clause was
  added to `markFailed`'s CAS and then removed as unreachable (see below).
- Tests: `WsClientFrameTest`, `MessageRepositoryTest`, `WsSyntheticSocketTest`, plus a source
  tripwire in `DecoySendPairingTest` pinning the coordinator wiring and its ordering.

## Constraints this had to satisfy — verify each independently

1. **A cover frame's rejection must never surface to the user.** Cover traffic is invisible by
   design. The claim is that this holds *structurally*: a cover envelope never creates a `Message`
   row, so `markFailed` finds nothing. **Attack that** — is there any path where a decoy's
   rejection becomes user-visible, or where a cover id could collide with a real message's id?
2. **The retry path must not resurrect the R-U3-1 class** (cover must never precede or compete with
   a real send; a retry IS a real send). `MessagingCoordinator.retry` re-enters the normal send
   choke point. **Verify it, including ordering and the confined worker.**
3. **The cover yield must not become conditional on attribution.** An unattributable rejection still
   means the budget is contended. Is the tripwire that pins this actually sufficient?
4. **`store_failed` must fail the message** — the relay does not hold the envelope.

## Attack specifically

1. **The echoed id as an attack surface.** The relay is conceded and can echo any well-formed UUID.
   What is the worst a hostile relay can do with this? Consider: failing a message it actually
   stored (inducing a duplicate on retry), ids from another conversation, ids of incoming mail,
   repeated ids, and whether any of it is worse than what the relay could already do by dropping.
2. **The removed `isMine` guard.** `markFailed`'s CAS accepts SENDING/SENT. The argument for removal
   is that `addIncoming` forces DELIVERED, so no incoming message is ever in an acceptable state,
   making `isMine` unreachable. **Find a counterexample** — any path that puts a not-ours message
   into SENDING or SENT, including restore-from-disk, migration, upsert, or test-only APIs. If one
   exists the guard was reachable and its removal is a defect.
3. **Null-id handling.** Is falling back to the previous behaviour genuinely correct, or does it
   leave a state where the user is still stuck on SENDING with no path out? Consider a
   `rate_limited` with no id for a send that will never be retried.
4. **State-machine interactions.** `markFailed` → FAILED → `retryable` → SENDING. Can a late or
   duplicated error frame corrupt this, resurrect a burned/removed message, race the TTL scheduler,
   or fail a message mid-retry?
5. **The tripwire itself.** It matches normalised source text. Can the wiring be defeated while the
   tripwire stays green? Does it over-constrain — would a legitimate refactor fail it for no reason?
6. **Anything else.** Threading (the callback runs on the socket's inbound dispatch thread, not the
   confined worker — is `markFailed` safe there?), reentrancy, exceptions escaping into the socket
   dispatch path, or a kdoc/comment claim the code does not support.

## Known gap, declared — judge whether it is acceptable

**No test constructs `MessagingCoordinator`**, so the attribution is covered only by a source
tripwire, not behaviourally. The constructor needs `Context`, `NotificationScheduler`,
`SignalProtocolManager` and more, which is a Robolectric-scale harness and a separate unit of work.
The substrate is tested behaviourally (wire normalisation, repository CAS, synthetic socket). **Is
that an acceptable position for a send-path change, or is the harness a merge blocker?** Say so
plainly.

## Calibration

- **P1** — a real message is lost, corrupted, or made undeliverable; the user is shown a false
  state; a decoy surfaces to the user; or the client discloses something an observer could not
  otherwise see.
- **P2** — the fix does not actually fix the defect in some reachable case, or degrades cover traffic.
- **P3** — a guard that does not guard what it claims, a doc/comment inaccuracy, hygiene.

Do not report style. Every finding needs: file and line, a **concrete reachable sequence**, the
wrong outcome, and why the tests miss it.

## Output

```
# FINDINGS
(one section per finding: ID, severity, file:line, the sequence, the outcome, why tests miss it)

# CONFIRM-OR-REFUTE
(numbered 1–6 above, each CONFIRM or REFUTE with the source evidence)

# MISSING CONTEXT
(anything you could not verify, and what would settle it)

VERDICT: CLEAN | FINDINGS (n P1, n P2, n P3)
```

A CLEAN verdict is the absence of a finding, not a proof of correctness — say what you checked.
