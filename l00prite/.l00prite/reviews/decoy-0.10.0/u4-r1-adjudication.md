# U4 review round 1 — adjudication

Two lenses, blind to each other, against `e7e1a41b`. **One row per PART**, per the standing rule
that a multi-part finding carried across as one row loses the parts nobody re-read.

| # | Lens | Sev | Finding | Verdict | Action |
|---|---|---|---|---|---|
| 1 | Codex U4-R1 **and** Grok F1 | P1 | `applyTransportLocked` returns null when the REAL socket is DISCONNECTED, so `applyTransport` bails and the SYNTHETIC socket is never redialled — left connected on the transport the user just switched away from | **UPHELD** | Per-socket decision: real keeps its state gate, synthetic no longer gated |
| 2 | Grok F2 | P1 | `start()` can reopen the socket after `stop()` — `stop` is non-suspending, cannot take the connect mutex, and runs in full between start's stopped-check and its dial | **UPHELD** | Check-and-dial moved under the same monitor `stop` uses for its disconnect |
| 3 | Codex U4-R2 / Grok F3 | P2 | `reconnect()` clears the latch unconditionally, so a `start` parked in `accessToken()` dials again after the reconnect already did | **UPHELD** | `AtomicBoolean` latch → `Mutex`; the second caller waits instead of racing |
| 4a | Grok F4 (part 1) | P2 | No admission bound on burn/reply jobs; an inbound flood lets cover work grow without limit and contend with the real send path | **UPHELD** | `MAX_OUTSTANDING_WORK = 64`; acks still fire past the cap |
| 4b | Grok F4 (part 2) | P2 | `CoverPressure` is blind to the SYNTHETIC socket's own `rate_limited` — the relay can throttle the cover account while this side keeps emitting into the refusal | **UPHELD** | `WsSyntheticSocket` routes `rate_limited` into the shared meter |
| 5 | Codex U4-R3 | P3 | The injection pin asserts an identifier *spelling*, not its origin: rebinding `syntheticWs` to the real client keeps it green while carrying U3's disconnect exemption | **UPHELD** | Binding itself pinned, single-occurrence |
| 6 | Grok F5 | P3 | `accessToken`'s kdoc says it "may have to refresh"; production never does — `WsSyntheticSocket` drops `onAuthExpired` | **UPHELD** | Wording corrected; the residual stated plainly |

**All seven upheld. No finding was argued down.** Codex's P1 and Grok's F1 are the same defect found
independently, which is the strongest signal round 1 produced — and my own comment three lines below
that code claimed the property the wiring did not have.

## Both lenses REFUTED the changed-U3-tripwire concern (review item 2)

Grok: "cannot keep all green while disconnecting the **real** socket outside allowed owners, or
while wiring pressure to a no-op queue." Codex refuted it only *because* of the spelling gap, which
is row 5 and is now closed. The receiver-typed exemption itself was accepted by both as coherent.
**Residual both name and I am carrying forward: these pins are lexical.** A rename or an
`alsoDisconnect` helper defeats them. That is the standing limit of source tripwires in this repo,
not a U4 regression.

## Codex's one open item — relay-side, not resolvable here

Whether an authenticated socket can receive only envelopes addressed to its own account, and whether
account ids are globally unique. R-U4-1's guard does not depend on either (it drops on `sender_id`
regardless), but the *residual* about a hostile relay forging `sender_id` does. Already recorded in
§4.4 as granting no new power.

## Evidence after the fixes

`ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`
→ **BUILD SUCCESSFUL, Gradle exit 0, 794 tests / 0 failures / 0 errors / 3 skipped** (784 → 794).

**Fix-targeted mutations: 6 applied, 6 discriminated** — but only after **four** test defects were
found by the sweep itself, which is the round's other lesson:

1. The double-dial test asserted **counts**; fixed and unfixed both produce 2 dials and 1 disconnect.
   What the mutex buys is **order** — a disconnect always separates the two dials.
2. The start-after-stop test ran on the test scheduler, where the window between the check and the
   dial cannot be interleaved at all. It needed real threads with the dial held open.
3. The same test then released the dial **before the stopper thread had run**, so it passed for the
   wrong reason.
4. And then it **joined the stopper but not the dialer**, so it read `open` before the dial wrote it.

Three consecutive fixes to one test, each of which still passed under the mutation. The rule stands
and is worth restating: **an assertion that passes for the wrong reason is indistinguishable from one
that passes for the right reason until you mutate the code.**
