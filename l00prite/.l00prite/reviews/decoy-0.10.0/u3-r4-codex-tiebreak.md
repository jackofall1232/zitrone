# TIE-BREAK — one severity question and one design dilemma. Read-only.

You are the tie-breaker. Two blind reviewers of the same code **converged on the same defect** but
split on severity, and their combined findings expose a dilemma neither resolved. Rule on both.

Repo: `/root/zitrone`, branch `feat/0.10.0-decoy-u3-pairing` @ `165abb37`.

## Background

Cover traffic pairs every real outbound message with a same-length synthetic frame. Two governing
requirements:

- **R-U3-1 (ABSOLUTE, with supremacy clause):** a real send is never blocked, failed, materially
  delayed, reordered or made less durable by cover traffic.
- **R-U3-3:** failure must be uniform, never intermittent — an unpaired frame, or a pair **split
  across a TLS connection boundary**, is a *marked* frame.

**Precedent (already ruled):** an undrained disconnect that splits a pair across a TLS boundary,
correlated with a transport change, was ruled **P1** — on the reasoning that a split pair is a
*stronger* signal than a missing cover frame, because it lets an observer link frames across
connection boundaries and ties the marked frame to an observable infrastructure event.

**Round 4's central structural claim:** terminal teardown is dispatched onto `MessagingCoordinator`'s
`limitedParallelism(1)` confined worker, so it runs strictly before or after a send's
publish-then-admit slice, never inside it. That is the argument the whole fix rests on.

## THE DEFECT (both reviewers agree it is real)

`reconnectTransport` (the Tor-toggle path) reuses `runTerminalTeardownOnConfinedWorker`, whose
**250 ms fallback runs the teardown lambda on the CALLING thread** when the worker doesn't respond in
time.

For `stop()` the fallback is safe: it sets `transportInvalid = true`, refusing late admissions.
**`quiesce` deliberately leaves `transportInvalid` false and the register open.** So when the fallback
fires during a transport change: the calling thread drains an empty register, disconnects the old
socket and dials a new one — while a send coroutine on the worker is mid-slice, having already put
its real frame on the *old* socket. It then resumes, admits, sleeps its gap, and emits the cover frame
on the *new* socket. **Split pair across a TLS boundary, correlated with a transport change.**

**One reviewer's sharper observation:** no coroutine suspension is needed for this interleave. The
"uninterruptible slice" argument only holds against teardown running *on the worker* — **and the
fallback has just taken teardown off it.**

## QUESTION 1 — severity

- **Position A: P1.** Verbatim the signal class already ruled P1, and worse than a bounded defect:
  the fallback *structurally defeats* the confinement argument the entire round-4 fix rests on,
  precisely when it fires. Also undeclared — the residual note covers `stop()` only.
- **Position B: P2.** Two stacked preconditions (worker busy >250 ms **and** a send mid-slice at that
  moment) bound the blast radius; the reviewer holding this explicitly deferred to the adjudicator.

Precedent on this project: *frequency and trigger-window width are inputs to remediation priority, not
severity class*; and against an absolute requirement, blast-radius arguments are a category error.

## QUESTION 2 — the dilemma the fix must resolve

The obvious remedy (lengthen or remove the fallback for `quiesce`) may be unsafe:

`applyTransportLocked` holds `transportLock` and calls `reconnectTransport`, which blocks on the
worker. `stopSession` — reachable **from that same worker** via `deleteAccountAndWipe → onConfirmed →
lock` — also takes `transportLock`. So: transport thread holds the lock and waits on the worker; the
worker waits for the lock. **The 250 ms bound is what currently breaks that cycle**, a justification
neither the implementer nor either reviewer stated.

Verify that cycle against the source, then rule: **is there a construction that closes the split-pair
defect without reinstating the deadlock?** Consider at least: making `quiesce`'s fallback refuse
admission the way `stop()`'s does; making the transport swap itself confined; making admission
socket-identity-aware so a cover frame is only emitted on the connection that carried its real frame;
or eliminating the calling-thread fallback for the non-terminal path specifically.

Be concrete and cite source. If every option has a cost, say which cost is right to pay and why.
