# U3 FIX ROUND 4 of 6 — the composed fix: a success signal, and teardown on the send worker

Branch `feat/0.10.0-decoy-u3-pairing`, 2026-07-28. Round 3 raised severity (2 P1 → 4 P1) and two of
the four were new — the fix-introduces-defects signature. **One of the four was the architect's own
instruction**, and one was an impossibility claim of mine that a reviewer refuted with a
construction. Both are recorded here as such.

## The construction, because everything else follows from it

Round 3 declared a residual and called it forced: between `ws.sendMessage` returning and the pairing
registering itself with teardown, a concurrent `stop()` can slip past; closing it seemed to require
registering *before* the publish — cover work and a lock in front of a real send — which R-U3-1
forbids absolutely.

**That argument was unsound and Codex supplied the counter-construction. I have implemented it and it
works.** The window does not need to be *atomic* with the handoff. It needs to be *serialised*
against teardown, and `MessagingCoordinator` already owns a serialisation point every send goes
through: `confined`, a `limitedParallelism(1)` worker. Terminal teardown is now **enqueued on that
worker**. It therefore runs strictly before a send's slice or strictly after it, never inside — and
because there is no suspension point between the publish tail and the pairing's admission, the slice
is uninterruptible. **No lock and no cover-side instruction was added in front of any real send.**

The other half of R-U3-5 step 1 — "stop admitting new real sends" — is an `acceptingSends` volatile
flag read at the top of every send coroutine, before any crypto, any durable write and several
suspension points before the barrier. It is nowhere near the `K` window, takes no lock, and refuses
sends that were doomed to hit a dead socket anyway. Round 3's claim that step 1 was not jointly
satisfiable with real-first was simply wrong.

## What each finding became

| # | Finding | Result | Structural or guarded |
|---|---|---|---|
| W1 | `publishOutgoing`/`publishReceipt` returned `Unit`; cover ran on all three outcomes, two of which emit a lone decoy | Both tails return "handed to the relay"; all three call sites are `if (publish…) cover(…)` | **Guarded** — a source tripwire pins the dependence, not adjacency. Kotlin cannot make it structural without putting a wrapper in the `K` window, which R-U3-1 forbids. |
| W2 | The drain's 100 ms deadline abandoned any build that overran it — "non-suspending" bounds suspension, not time | Deadline, condition variable, `resolved` flag and wait loop **deleted**. A pairing is admitted only once its frame is built, so the drain has nothing to wait for | **Structural** — there is no wall clock left in the class |
| W3 | `ZitroneApp.applyTransportLocked` disconnects without draining; split pair across a TLS boundary. Ruled P1 | New non-terminal `CoverTraffic.quiesce`: same drain, transport not invalidated, pairing resumes over the new socket. Dispatched on the confined worker | Fixed, not excepted. The tripwire's deliberate carve-out is **gone** — it now reads both disconnect owners |
| W4 | The impossibility argument, refuted by construction | Implemented (above). Residual **closed**, not accepted | **Structural** for the pairing/teardown ordering; **guarded** for "the coordinator really dispatches onto the worker" |
| W5 | `ensureProvisioning` CAS-then-assign race | Whole method under the teardown lock; `stop()` cancels under the same lock | **Structural** — only two interleavings exist |
| W6 | The tripwires did not pin what they claimed | All three rewritten; see below | Guarded, and honestly labelled |

## What build-then-admit cost and bought

The cover frame is now built *before* the pairing is admitted. It is still strictly after
`ws.sendMessage`, so `K` is byte-for-byte the pre-U3 one and R-U3-1 is untouched. What it buys is
that the register never holds an unbuilt pairing — which is what lets the wait, the deadline, the
`resolved` flag and the condition variable all be deleted. Four moving parts and one P1, for one
reordering.

**Honest note from mutation testing:** reverting to round 3's admit-then-build order (M5) leaves the
whole suite green, and correctly so. Once teardown is serialised on the worker, both orders are
behaviour-equivalent — the deadline, not the order, was the defect. Build-then-admit is chosen
because it makes the deadline *unnecessary* rather than merely unreached.

## The tripwires, re-derived (W6)

- **Interface surface.** Was: `cover` has no `kotlin.Function` parameter — pinned exactly one shape;
  a custom SAM, a `Runnable` or a differently named method walked past it. Now: the entire declared
  method set of `CoverTraffic` is pinned by name and parameter list. Adding *any* method fails
  (verified: adding `fun handoff(publish: Runnable)` fails it).
- **Call site.** Was: "the previous statement is a publish tail" — statement adjacency, which was
  **true while W1 was live**. Now: every `coverTraffic.cover(` is the body of an `if` on a publish
  tail's result, the count is three, and both tails must declare `Boolean` and return `true` from the
  `ws.sendMessage` branch and nowhere else. Verified against three separate regressions including a
  vacuous guard (`ws.sendMessage(envelope) || true`).
- **Disconnect.** Was: one file, one exact source line, with `ZitroneApp` deliberately excluded. Now:
  both files, comments stripped, whitespace normalised, brace-walked to the enclosing lambda — so a
  correct multi-line lambda passes and a helper that hides the disconnect fails.
- **New — the dispatch.** The serialisation that closes W4 is a property of *where* the coordinator
  runs teardown, which no behavioural test in the pairing suite can see. Pinned: exactly one
  `coverTraffic.stop {` in the file, inside a named method; `stop()` goes through the confined
  dispatch; the account-delete path (already on the worker) does not; and `acceptingSends = false`
  precedes the teardown on both paths and is consulted before the durability barrier on all three
  send paths.

## Mutation results — 13 applied, 12 discriminated

| Mutation | Caught by |
|---|---|
| M1 cover call unguarded at both `publishOutgoing` sites | call-site tripwire |
| M2 publish tail returns `true` for a deleted contact | call-site tripwire |
| M11 vacuous guard `ws.sendMessage(envelope) \|\| true` | call-site tripwire |
| M3 teardown run on the calling thread | dispatch tripwire |
| M4 teardown dispatched off the confined worker | dispatch tripwire |
| M12 `acceptingSends = false` moved after the teardown | dispatch tripwire |
| M13a/b send gate removed from `deliverText` / `sendReadReceipt` | dispatch tripwire |
| M6 teardown stops draining | slow-build test + 3 behavioural tests |
| M8 `ZitroneApp` disconnects directly again | disconnect tripwire |
| M7 `quiesce` delegates to `stop` | "a transport swap is NOT a teardown" |
| M9 `ensureProvisioning` CAS outside the lock | deterministic W5 test |
| M10 extra callable method on the seam | interface-surface tripwire |
| **M5 admit-then-build (round-3 order)** | **not caught — behaviour-preserving under confinement; see above** |

Two **test-side** mutations were also run, to check that the two new behavioural tests pin
confinement rather than merely passing: moving teardown off the worker (`kotlin.concurrent.thread`)
fails "teardown serialised on the send worker never strands a pairing" and, with a slow build, also
fails "the drain has no wall clock". Both discriminate.

## Evidence

`ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug` from
`apps/android` — **BUILD SUCCESSFUL, exit 0**. 716 tests across 78 classes, 0 failures, 0 errors;
`DecoySendPairingTest` is 35 of them.

## Residuals, declared rather than claimed away

1. **`MessagingCoordinator.stop()` blocks on the confined worker for at most 250 ms**
   (`TEARDOWN_QUIESCE_MS`) before falling back to running teardown on the calling thread. The bound
   is on *waiting for the worker to become free*, not on any cover-side work. It exists because
   `UnlockController` closes the vault runtime immediately after `stop()` returns: a lock that can
   hang and never wipe key material is worse than any framing defect. On expiry, that one teardown
   degrades to round-3 behaviour.
2. **The confinement contract is a contract, not a type.** `DecoySendPairing` cannot enforce that its
   caller is confined. The lock is kept so a violation degrades rather than corrupts, and the
   coordinator side is pinned by the dispatch tripwire — a guard, not a proof.
3. Unchanged from round 3: cross-send `sendLimit` preemption (relay-side), OkHttp write coalescing,
   R-U3-4's uncovered frame on a build refusal.

## What the adjudication got right, and the one thing it did not

It was right on all six, and the composed-fix instruction was the correct call — W1 and W4 really do
compose, and W2 and W3 fall out of them rather than needing separate repairs.

The one correction: the adjudication describes W2 as needing the drain's timeout removed *because*
the drain would no longer race admission. That is true but understates it. Under round 3 the deadline
was reachable *only* because teardown ran off the worker; serialising teardown alone would have made
it unreachable while leaving it in the code. Deleting it needed the second change — admitting only
built pairings — and that is why the fix is a reordering inside `cover()` and not just a dispatch
change. Removing one without the other leaves either a live deadline or an unbuilt-pairing register
with nothing to wait on it.
