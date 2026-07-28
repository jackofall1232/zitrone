# Adversarial review — Zitrone 0.10.0 decoy traffic, **Unit U4**, round 5

**READ-ONLY.** Do not modify, create or delete any file. Do not run mutating commands. Report only.

You are one of two reviewers working **blind to each other**. Do not assume anything has already
been found. Repo root: `/root/zitrone`. Branch: `feat/0.10.0-decoy-u4-synthetic-receive`.

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
constructs a counterexample against each.

**This matters to how you should review.** U3 took seven rounds and four lenses because R-U3-1 and
R-U3-3 were written as guarantees about **outcomes the network can always falsify**; three of four
lenses concluded the feature was unshippable, reasoning correctly from a premise that should never
have been written. §4.4's requirements are therefore deliberately written as rules about **our own
code's behaviour**.

So, two things are in scope and you should say which you are doing:

1. **Does the code satisfy the requirement as written?** (the usual review)
2. **Is the requirement itself wrong** — unsatisfiable as literally stated, or so weak it permits a
   real defect? If you think a requirement is the defect, **say so explicitly**; that is a valid and
   valuable finding here, not out of scope.

## Files

Implementation:
- `apps/android/app/src/main/java/com/zitrone/app/decoy/DecoyInboundSession.kt` — U4's core
- `apps/android/app/src/main/java/com/zitrone/app/decoy/WsSyntheticSocket.kt` — the production socket adapter
- `apps/android/app/src/main/java/com/zitrone/app/decoy/DecoyEnvelopeBuilder.kt` — `buildReply` is new in U4
- `apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt` — the R-U4-1 guard in `onMessageDeliver`, and the `isSyntheticSender` constructor parameter
- `apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt` — wiring: `SessionContainer` init, `applyTransport`, `applyTransportLocked`

Context it must not break:
- `apps/android/app/src/main/java/com/zitrone/app/decoy/DecoySendPairing.kt` — U3, including the `CoverTraffic` interface U4 decorates
- `apps/android/app/src/main/java/com/zitrone/app/decoy/CoverPressure.kt` — the shared yield policy
- `apps/android/app/src/main/java/com/zitrone/app/net/WsClient.kt`

Tests:
- `apps/android/app/src/test/java/com/zitrone/app/DecoyInboundSessionTest.kt`
- `apps/android/app/src/test/java/com/zitrone/app/DecoyReplyBuilderTest.kt`
- `apps/android/app/src/test/java/com/zitrone/app/DecoyU4SourceTripwireTest.kt`
- `apps/android/app/src/test/java/com/zitrone/app/DecoySendPairingTest.kt` — **two U3 tripwires were changed by U4; scrutinise both**

## Four rounds are done. Nineteen findings, all upheld. This round reviews round 4's fixes

**Round 1:** 7 findings (2 P1). **Round 2:** 7 findings (0 P1; the two lenses disagreed and the
disagreement was the finding). **Round 3:** 1 finding, other lens CLEAN. **Round 4:** 4 findings,
**0 P1 and 0 P2 from both lenses.**

Round 4's four, and their fixes — **this is the newest code and the main target**:

1. **A deniability leak.** The R-U4-1 guard called `diag()`, and `BootDiagnostics.record` does
   `file.writeText` — so every dropped cover envelope wrote a timestamped line to
   `boot-diagnostics.log`, shown in Settings → Diagnostics. Durable evidence that this device ran
   cover traffic. **Fixed:** `diag()` removed; a tripwire forbids `diag(`, `Log.`, `println` and
   `BootDiagnostics` in the guard and in both U4 files.
2. **`disconnect()` as a callable reference** (`val d = ws::disconnect; d()`) escaped the
   ownership scan. **Fixed:** a second scan forbids `::disconnect` app-wide.
3. **A tripwire deleted by an earlier edit and never restored** — the pin that the synthetic redial
   sits outside the real socket's connection-state gate. **Fixed:** restored, and it now asserts
   *position* rather than token presence.
4. **A same-file helper** `fun disconnectClient(ws: WsClient)` would inherit `WsSyntheticSocket`'s
   disconnect exemption and could close the REAL socket from any caller. **Fixed:** no
   `WsClient`-typed declaration may appear anywhere in that file.

**Attack those four fixes.** Are the new logging bans complete — is there any other durable sink
U4 can reach (a `Log.` alias, an exception message carrying an envelope field, a `toString`)? Does
forbidding `::disconnect` app-wide break or mis-scope anything? Does the restored position assertion
actually pin what it claims, and can the redial be moved somewhere it still passes? Is the
`WsClient`-declaration ban on `WsSyntheticSocket` complete, or is there a way to reach a real socket
from that file without naming the type?

**And the standing question, now with four rounds of change behind it:** is any requirement in
§4.4 wrong — unsatisfiable as literally stated, or so weak it permits a real defect? Round 4 already
showed R-U4-3's "adds no durable-state writer" was too narrow to catch a durable *diagnostic*. Say
so plainly if you find another.

## Attack the following specifically

1. **R-U4-1 — can a cover frame become a message?** The guard is in `onMessageDeliver`, keyed on
   `isSyntheticSender`, placed before `signal.decrypt`. Find any path by which an envelope from the
   synthetic account reaches decryption, the message store, the roster, the unread count, or a
   notification. Consider: the guard reading a vault the session has torn down; the synthetic id
   being null at the moment of the check; a *real* contact whose account id could equal the
   synthetic one; and whether acking **bare** (rather than `ackDurable`, which the deleted-contact
   branch beside it uses) can lose a real message. The code argues bare is correct here — test that
   argument.

2. **The two changed U3 tripwires in `DecoySendPairingTest.kt`.** U4 exempted the synthetic socket
   from the disconnect-ownership guard with a **receiver-typed** exemption, and rewrote the
   pressure-wiring assertion after hoisting `CoverPressure`. **A weakened guard is a defect.** Can
   you now hide a disconnect of the REAL socket, or a mis-wired pressure meter, and keep every test
   green? The exemption's safety depends on `WsSyntheticSocket` only ever receiving the decoy
   client — check whether that is really pinned.

3. **R-U4-4 — the yield.** The send-back consults the same `CoverPressure` as the send pairing; the
   ack and burn deliberately do **not** yield. Is the exemption reasoning sound, or can an unbounded
   inbound flood turn the synthetic side into a source of contention for the real socket? Note both
   sockets share one uplink, and the send-back is addressed to the **real** account.

4. **Lifecycle.** `start` / `reconnect` / `stop`, the `bindTo` decorator, and the transport swap in
   `ZitroneApp.applyTransport`. Can the synthetic socket outlive a vault lock? Can a transport
   toggle permanently kill cover traffic, or leave the synthetic socket on the old endpoints? Are
   there races between lazy provisioning calling `start()` and teardown?

5. **R-U4-2 / R-U4-3 — no crypto, no durable writer.** These are claimed as properties of
   `DecoyInboundSession`'s dependencies. Verify by reading its constructor. Does anything in U4
   write durable state, mutate the vault, or advance a ratchet?

6. **`buildReply`.** It always emits established-session shape and mirrors the received ciphertext's
   byte length. Is the resulting frame plausible? Can it produce a malformed or distinctively-sized
   envelope? Is the in-memory reply counter a leak (it restarts at 0 per process)?

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
