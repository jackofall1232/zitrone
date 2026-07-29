# Adversarial review — Zitrone 0.10.0 decoy traffic, **Unit U4**, round 6 — THE FINAL ROUND

**READ-ONLY.** Do not modify, create or delete any file. Do not run mutating commands. Report only.

You are one of two reviewers working **blind to each other**. Do not assume anything has already
been found. Repo root: `/root/zitrone`. Branch: `feat/0.10.0-decoy-u4-synthetic-receive`.

**This is round 6 of a hard-capped 6.** Whatever you find or do not find, there is no round 7: a
clean convergence here is the last gate before the maintainer's merge decision, and an upheld
finding here blocks the merge. Weigh your CLEAN accordingly — say precisely what you checked.

## Review the WHOLE UNIT, not the diff

A prior release shipped a real defect because rounds 1–2 were scoped to a fix diff and the original
unit went unexamined. **Read U4 as a complete feature**, including code it merely touches.

## What Zitrone is, and what cover traffic is for

Zitrone is a zero-knowledge, plausible-deniability encrypted messenger. The relay stores opaque
ciphertext and sees cleartext `sender_id`/`recipient_id` on every envelope — **the relay is conceded
in the threat model.** Cover traffic defends against a **network observer**, not the relay.

Cover traffic is explicitly **the outer layer, not the core**: Signal Protocol holds message
content, the vault holds deniability, Tor/I2P hold anonymity. A missing cover frame is a lost layer
of ambiguity, never a loss of confidentiality. **A real message must never be harmed to produce
cover.**

## What U4 is

U1 provisioned a synthetic relay account per vault. U2 built envelopes that mirror a real send's
frame exactly. U3 pairs a cover envelope with every real send, **real frame first, always**.

**U4 is the synthetic side.** The synthetic account opens its own relay socket, acks the cover
envelopes addressed to it, burns them after a short drawn delay, and occasionally sends one back —
so the cover exchange is not conspicuously one-directional and produces control-channel traffic of
its own.

## The requirements are in the spec, and were falsified BEFORE the code was written

Read **§4.4 of `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md`** first. It states R-U4-1 … R-U4-6 and
constructs a counterexample against each. **R-U4-3 was reworded in round 5** — it now forbids
*reaching* an existing durable writer, not only adding one. Review the reworded text as the
requirement.

Two things are in scope and you should say which you are doing:

1. **Does the code satisfy the requirement as written?** (the usual review)
2. **Is the requirement itself wrong** — unsatisfiable as literally stated, or so weak it permits a
   real defect? If you think a requirement is the defect, **say so explicitly**; that is a valid and
   valuable finding here, not out of scope.

## Files

Implementation:
- `apps/android/app/src/main/java/com/zitrone/app/decoy/DecoyInboundSession.kt` — U4's core
- `apps/android/app/src/main/java/com/zitrone/app/decoy/WsSyntheticSocket.kt` — the production socket adapter; **changed in round 5: it no longer accepts a `diag` parameter**
- `apps/android/app/src/main/java/com/zitrone/app/decoy/DecoyEnvelopeBuilder.kt` — `buildReply` is new in U4
- `apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt` — the R-U4-1 guard in `onMessageDeliver`, and the `isSyntheticSender` constructor parameter
- `apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt` — wiring: `SessionContainer` init, `applyTransport`, `applyTransportLocked`; **changed in round 5: the synthetic socket construction**

Context it must not break:
- `apps/android/app/src/main/java/com/zitrone/app/decoy/DecoySendPairing.kt` — U3, including the `CoverTraffic` interface U4 decorates
- `apps/android/app/src/main/java/com/zitrone/app/decoy/CoverPressure.kt` — the shared yield policy
- `apps/android/app/src/main/java/com/zitrone/app/net/WsClient.kt`
- `apps/android/app/src/main/java/com/zitrone/app/diagnostics/BootDiagnostics.kt` — the durable sink U4 must not reach

Tests:
- `apps/android/app/src/test/java/com/zitrone/app/DecoyInboundSessionTest.kt`
- `apps/android/app/src/test/java/com/zitrone/app/DecoyReplyBuilderTest.kt`
- `apps/android/app/src/test/java/com/zitrone/app/DecoyU4SourceTripwireTest.kt` — **three tests changed/added in round 5**
- `apps/android/app/src/test/java/com/zitrone/app/DecoySendPairingTest.kt` — **the disconnect-ownership scan gained an app-wide `"disconnect"` string-literal ban in round 5**

## Five rounds are done. Twenty-three findings, all upheld. This round reviews round 5's fixes

**Round 1:** 7 findings (2 P1). **Round 2:** 7 (0 P1). **Round 3:** 1, other lens CLEAN.
**Round 4:** 4, all P3. **Round 5: both lenses independently converged on the same P1** — the
synthetic socket's `diag` parameter was wired to `BootDiagnostics.record` in `ZitroneApp`, so the
cover socket's ENTIRE lifecycle (handshake, connected, closed, failure) was written durably to
`boot-diagnostics.log` on every unlock of a decoy-relay vault. Round 4 had banned `diag(` *calls*
in the U4 files; the defect was a *parameter*, forwarded, at a construction site no tripwire read.

Round 5's fixes — **this is the newest code and the main target**:

1. **The `diag` parameter was DELETED from `WsSyntheticSocket`** — the claim is structural: there
   is no longer any argument through which a sink can reach the synthetic socket; `WsClient`'s own
   default `{}` is the sink. The `ZitroneApp` wiring line is gone. The tripwire now bans the bare
   token `diag` in both U4 files and scans the production construction block for
   `diag` / `Diagnostics` / `Log.` / `println` / `record(`.
2. **R-U4-3 reworded** (a lens found the requirement itself defective): it now forbids invoking an
   *existing* durable writer, diagnostic sinks included, and its falsification check covers the
   construction wiring.
3. **The redial tripwire now requires the segment between the real socket's gate and the synthetic
   redial to be NOTHING but the gate's closing brace** (`^\s*\}\s*$`) — round 5 showed the old
   `redial > gateEnd` position check passed a second gate or a bare `return` inserted between them.
4. **Reflection bans**: no reflection-lookup token (`javaClass`, `::class`, `Class.forName`,
   `getMethod`, `getDeclaredMethod`, `java.lang.reflect`, `kotlin.reflect`, `MethodHandles`) in
   either U4 file, and no `"disconnect"` string literal anywhere in app source — round 5 showed
   `javaClass.getMethod("disconnect").invoke(ws)` evaded every token scan from any file. Declared
   residual: a concatenated or computed name still slips a lexical scan.

**Attack those four fixes:**

(a) Is there any REMAINING route from `WsSyntheticSocket` or `DecoyInboundSession` to ANY durable
    sink — not just `BootDiagnostics` — including via constructor parameters of any name, via
    `WsClient`'s other collaborators, via an exception that carries state into a caller that logs,
    or via `OkHttpClient`'s own hooks (interceptors, `EventListener`) that production wiring could
    attach?
(b) Does deleting the `diag` parameter break anything that legitimately needed it? The claim is the
    synthetic account has no UX and nothing reads its diagnostics — verify no code or test relied
    on it.
(c) Can the brace-only segment assertion be evaded by moving or restructuring — relocating the
    redial above the gate, wrapping `applyTransport` itself, a second `applyTransport`-like path
    that swaps transports without this code, or making the FIRST gate's closing brace not be the
    brace the test finds?
(d) Is the reflection/string-literal ban set complete for the disconnect surface? The residual is
    declared for computed names — is there an UNDECLARED evasion class (method handles by
    signature, JNI, a Kotlin synthetic accessor, serialization tricks)? Distinguish "the declared
    residual, restated" from a genuinely new class.

**And the standing question, with five rounds behind it:** is any requirement in §4.4 — including
the round-5 rewording of R-U4-3 — still wrong: unsatisfiable as stated, or weak enough to permit a
real defect?

## Attack the following specifically

1. **R-U4-1 — can a cover frame become a message?** The guard is in `onMessageDeliver`, keyed on
   `isSyntheticSender`, placed before `signal.decrypt`. Find any path by which an envelope from the
   synthetic account reaches decryption, the message store, the roster, the unread count, or a
   notification. Include the torn-down-vault and null-id timings, and whether the bare ack (vs
   `ackDurable`) argument still holds.

2. **The changed U3 tripwires in `DecoySendPairingTest.kt`**, including round 5's literal ban. A
   weakened OR over-broadened guard is a defect: does the `"disconnect"` literal ban mis-fire on
   anything legitimate, and can you still hide a real-socket disconnect keeping every test green?

3. **R-U4-4 — the yield.** Send-backs yield via the shared `CoverPressure`; acks and burns do not.
   Is the exemption still sound under a hostile relay flood, and do both sockets' queues still feed
   one meter after the round-5 construction change?

4. **Lifecycle.** `start` / `reconnect` / `stop`, `bindTo`, and the transport swap. Can the
   synthetic socket outlive a vault lock, stay on old endpoints, or be permanently killed by a
   transport toggle? Races between lazy provisioning and teardown?

5. **R-U4-2 / R-U4-3 (as reworded) — no crypto, no durable writer, no durable sink.** Verify from
   `DecoyInboundSession`'s constructor and from the production construction of `WsSyntheticSocket`.

6. **`buildReply`.** Plausibility of the established-session shape, length mirroring, failure
   behaviour, and the in-memory counter restart.

7. **Anything else.** Deadlock, lock ordering, unbounded growth, an exception escaping into a real
   send's path, or a claim in a kdoc or the spec that the code does not support.

## Calibration

- **P1** — a real send is harmed, a decoy surfaces to the user, crypto/durable state is corrupted,
  or the client discloses something an observer could not otherwise see.
- **P2** — cover traffic is degraded or the mechanism is defeated, with no harm to a real message.
- **P3** — a guard that does not guard what it claims, a doc/comment/spec inaccuracy, hygiene.

Weigh **disclosure vs degradation**: cover that goes quiet under load is acceptable; cover that
fails in a way revealing an event an observer could not already see is not.

Do not report style. Every finding needs: the file and line, a **concrete reachable sequence**, the
wrong outcome, and why the tests miss it.

## Output

```
# FINDINGS
(one section per finding: ID, severity, file:line, the sequence, the outcome, why tests miss it)

# CONFIRM-OR-REFUTE
(numbered 1–7 above, each CONFIRM or REFUTE with the source evidence)

# MISSING CONTEXT
(anything you could not verify, and what would settle it)

VERDICT: CLEAN | FINDINGS (n P1, n P2, n P3)
```

A CLEAN verdict is the absence of a finding, not a proof of correctness — say what you checked.
