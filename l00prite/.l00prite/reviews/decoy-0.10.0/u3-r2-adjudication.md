# U3 — ROUND 2 adjudication (Codex + Grok paired-blind, + Kimi K3 on two severity disputes)

Codex: `FINDINGS (2 P1, 0 P2, 1 P3)` · Grok: `FINDINGS (0 P1, 2 P2, 4 P3)` · **Kimi: P1 on both disputes.**
**Adjudicated union: 2 P1, 1 P2, 5 P3.**

Disjoint on the top finding for the fifth consecutive round: Codex found the process-death refutation
and Grok missed it; **Grok found a wiring defect Codex missed entirely** (V3 below).

## V1 — P1. The "impossible by construction" claim is FALSE, and it concealed a real loss path

The implementer justified the real-first structure with *"a process can only die at a suspension
point."* **That is false** — a coroutine may only *suspend* at a suspension point; the OS can kill
the process at **any instruction**, which this project's threat model explicitly assumes. After the
ratchet advances durably, execution still enters a suspend interface method and a coroutine state
machine before reaching the socket handoff. Death there loses the message.

**Severity dispute (Codex P1 / Grok P3) — Kimi ruled P1, on a textual reading neither reviewer made:**

> **"Materially" modifies "delayed", not "made less durable."** It stops insignificant scheduling
> overhead counting as a prohibited delay. It does **not** create a de minimis exception for reduced
> durability.

And the reductio that a materiality threshold is needed *fails*, because **code independently
required for the real send is not added "because cover traffic was attempted"** — the pre-existing
window is not cover's doing, but enlarging it is. If baseline kill-window is `K`, cover makes it
`K ∪ C`. The old window may be independently defective; that does not authorise cover to widen it.

**The actionable consequence, and the fix direction:** *cover-specific work can be ordered **after**
the real socket handoff.* Today `paired(cover, publish)` means entering cover's function **before**
`publish()` runs. Invert it — publish at the call site, then hand off to cover — and `C` becomes
empty. A false structural claim would have been a P3 doc fix **if the property held**; it does not,
and the assertion hid the gap.

## V2 — P1. Teardown disconnects the socket before stopping cover, and never drains in-flight pairs

`ws.disconnect()` at `MessagingCoordinator.kt:669`; `coverTraffic.stop()` at `:675`. `stop()` cancels
only the provisioning job — it does not cancel or drain pairings already sleeping in their gap. So on
vault lock: real frame published, gap sleeping, socket nulled, scope cancelled, the surviving
`finally` fires and `sendMessage` silently returns false. **On the wire: a lone real frame, then TLS
close.**

That marks a **deterministic, recognisable class** of unpaired real frames correlated with lock,
teardown and backgrounding — the exact observable the feature exists to eliminate, and precisely what
R-U3-3 calls worse than no cover at all. It also defeats the one guard round 2 deliberately kept.

**Severity dispute (Codex P1 / Grok P2) — Kimi ruled P1:** no payload is lost, but **P1 expressly
includes a deniability break**, and "bounded blast radius" is a priority argument, not a severity one.
The 5–50 ms width limits how *often* it fires, not what it *reveals* when it does.

**Kimi's fix constraint goes beyond either reviewer and is binding:** reordering `stop()` earlier is
**not sufficient**. The lifecycle must (1) stop admitting new sends and pairings, (2) stop
provisioning, (3) **cancel, complete or drain pairings already admitted**, and only then (4)
invalidate the socket. `stop()` does not currently own in-flight pairings at all.

## V3 — P2. U3's wiring silently un-asserted a U1 property (Grok only; Codex missed it)

U3's `provisioningStarted` CAS launches provisioning **once per session**. If that one call lands
while a durable back-off from a prior 429 is still in force, `provisionIfNeeded` returns false without
burning the attempt — and is **never called again**, because the CAS is already set. Cover stays off
for the rest of the session even after the window expires.

U1 explicitly pins the opposite: *"a back-off window that expires mid-session still gets its one
attempt."* U1's own test makes two direct calls; **the wired path makes one.** This is the
deletion/wiring class — a new caller silently retiring a property that only becomes observable once
provisioning is reachable, which U3 is the unit that made it.

## V4 — P3, 14th recurrence. §5 stale again

Still says U1 is deliberately unwired (U3 constructs it), still leads with the obsolete 640–643 B
figure, still says four review rounds, still says merge pending — U1 and U2 are merged. Plus Grok's
further P3s: the U3-A wording itself, and the §4.3/§5 drift.

## Note for the fix

V1 and V2 are the same shape as every P1 in this unit: **cover traffic placed where it can precede or
outlive the real send.** V1 puts cover work *before* the handoff; V2 lets cover work *outlive* the
socket. The unifying repair is the one already ruled: cover is strictly subordinate — it runs after
the real frame is committed, and it is torn down before the transport it depends on.
