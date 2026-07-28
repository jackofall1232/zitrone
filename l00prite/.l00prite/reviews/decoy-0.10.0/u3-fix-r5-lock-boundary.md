# U3 FIX ROUND 5 of 6 — the lock boundary, and a primitive that was doing two incompatible jobs

Branch `feat/0.10.0-decoy-u3-pairing`, 2026-07-28, on `4746de29`. Round 4 was the **first round in
seven where the two blind reviewers converged on the same top finding**, with severity falling — the
calibration rule (`failures.md`) reads that as the surface being exhausted rather than as anchoring.
The round-4 fix held everywhere except one reused primitive, and this round is about that reuse and
about the fact that **nothing tested the property the whole round-4 fix rests on.**

## X1 (P1) — the reuse, and why the obvious repair was the wrong one

`reconnectTransport` reused `runTerminalTeardownOnConfinedWorker`. Its 250 ms **caller-thread
fallback** is safe for `stop()` and only for `stop()`, because `stop()` invalidates the transport and
every late admission is refused. **`quiesce` deliberately leaves the register open** — that is what
lets pairing resume over the new socket — so when the fallback fired it drained an empty register on
the calling thread, replaced the socket, and left a send still inside its slice on the worker free to
admit a pairing and emit its cover frame on the NEW connection while its real frame had gone out on
the old one. Kimi's framing is the one of record and it is sharper than "an unjustified bound": **no
coroutine suspension is needed for the interleave, because the uninterruptible-slice argument only
ever held against teardown running ON the worker — and the fallback has just taken teardown off it.**
The fallback structurally defeated the confinement argument, precisely when it fired.

Lengthening or dropping the bound reinstates the verified five-step deadlock (`applyTransport` holds
`transportLock` → blocking reconnect waits on `confined` → `deleteAccountAndWipe` runs there → its
`onConfirmed` calls `lockIf` → `stopSession` takes `transportLock`). **So the fix is at the lock
boundary**, exactly as ruled:

- `ZitroneApp.applyTransportLocked` now *returns* the session that needs its live socket redialled
  instead of redialling it. Under `transportLock`: resolve endpoints, install them on the live
  `ApiClient`/`WsClient`, capture the `SessionContainer`. **Release the lock.**
- `applyTransport` then requests the reconnect on the captured session — outside the lock, confined
  to the coordinator's worker, **with no caller-thread fallback**.
- On the worker the swap is skipped if terminal teardown has begun or completed, and queued swaps
  are **coalesced by generation**, so one user action produces one reconnect.

### Where I deviated from the ruling, and why

The ruling says the reconnect "can wait for confinement without any fallback". **It does not wait at
all.** Waiting was the only reason the fallback existed, and nothing after the call depends on the
swap having completed: the endpoints are already installed under the lock, so every subsequent dial —
including `WsClient`'s own reconnect backoff — already uses the new transport. A wait with no bound
and no fallback would simply move the hang from `transportLock` to the resolver's collector
coroutine. Non-blocking is strictly stronger and removes the last unbounded wait on this path.

**A consequence worth stating rather than discovering:** because the request no longer blocks,
holding `transportLock` across it would not deadlock either. The lock-boundary change is therefore
not *load-bearing on its own today* — it is load-bearing as a pair with "no fallback", and the two
must not drift back together. That is why a tripwire pins the boundary as well as the dispatch.

## X5 (P2) — the tests named for confinement did not test confinement

This is the round's most important finding after X1, and it is the reason X1 survived a round that
claimed to establish the property. No test instantiated `MessagingCoordinator`; both round-4
"confinement" tests built their **own** `Executors.newSingleThreadExecutor()`; production dispatch was
pinned only by source strings; and **the fallback branch — the branch that carried the P1 — was never
executed by anything.**

`MessagingCoordinator` cannot be built in a JVM unit test (no Robolectric in this module; its
constructor wants `Context`, `SignalProtocolManager`, `ApiClient`, `WsClient` and four repositories).
So the dispatch was **extracted into production code that can be**: `CoverTrafficWorker`, with three
deliberately different entry points.

| Entry | Thread | Bound | Fallback | Used by |
|---|---|---|---|---|
| `runTerminalHere` | the caller's, which must already BE the worker | none | n/a | `deleteAccountAndWipe` |
| `runTerminalConfined` | the worker, else the caller after the bound | yes, **both** waits | yes — the key wipe must not hang | `stop()` |
| `requestReconnect` | the worker, **always** | none — it does not wait | **never** | `reconnectTransport` |

Seven behavioural tests now drive that production class: the real CAS, the real latch, the real
bounds, the real fallback, the real generation coalescing — including an end-to-end test that runs a
real `DecoySendPairing` on a real single worker over a socket whose **identity changes on a swap**, so
"the pair was split across a TLS boundary" is *observed* rather than argued.

**What is still guarded rather than structural:** that `MessagingCoordinator` reaches cover traffic
only through this class, and that `ZitroneApp` releases the lock first. Those are source tripwires,
and they are labelled as such.

## What each finding became

| # | Finding | Result | Structural or guarded |
|---|---|---|---|
| X1 | P1 — the quiesce fallback re-opens the split-pair class and defeats the confinement argument | Separate non-terminal entry point with no fallback and no wait; lock released before it is called | **Structural** — there is no code path on which a transport swap runs off the worker. The wiring is guarded. |
| X3 | P2 — the dispatch tripwire did not pin that the swap is confined | Rewritten: pins all three routes, the `scope.launch(confined)` in each, the *absence* of any wait in the reconnect path, and the lock boundary in `ZitroneApp` | Guarded, and now over all app sources rather than two files |
| X4 | P2 — the declared residual path was untested; the admit-then-build note understated it | The fallback branch is executed by a test that asserts exactly what it costs: an unpaired REAL frame, never a lone decoy, never a split pair | **Behavioural** |
| X5 | P2 — the confinement tests tested their own executor | Dispatch extracted to `CoverTrafficWorker`; seven tests drive the production class | Behavioural for the primitive; guarded for the wiring |
| X6 | P3 — the second `done.await()` was unbounded | Bounded by the same constant and the same rationale; a test wedges a worker that has claimed the teardown and asserts the caller still returns | **Behavioural** |
| X7 | P3 — a spec residual struck without replacement | Natural socket death mid-gap re-declared in §4.3 R-U3-5, with why it differs from the classes that section closes | Declared |
| X8 | P3 — the test-count claim did not match the tree | 34 was the truth, not 35; corrected in §5 and stated as an error rather than silently updated. It is now 41 | Corrected |
| X9 | P3 — three tripwire evasions survived | `coverTraffic . cover(` and `disconnect( )` are normalised away; the disconnect scan and the stop/quiesce counts read **every** Kotlin source, not two files | Guarded |

### Also fixed, from round 4's finding 6 (not adjudicated in, fixed anyway)

`a CancellationException out of the cover frame cannot skip the real publish` was **vacuous**:
`published++` ran before `cover()` was entered, so it passed for any cover behaviour — including
`emit` swallowing the `CancellationException`, which is the one throwable its contract forbids it to
swallow and the thing the test is named for. It now asserts the propagation. Mutation M12 confirms.

The other four tests that finding named (`:247`, `:572`, `:598`, `:518`) were re-derived and are
**left as they are, with what they actually pin stated here** rather than relabelled in a hurry with
one round left: since fix round 3 the publish happens at the *call site*, so "no cover-side code runs
before the real publish" is structural and no harness can fail it — those tests pin "the three
collaborators were called" and "launch order is preserved", which is worth having but is not the
property in their names. The named property is pinned by the call-site tripwire.

## What the admit-then-build note should have said (X4, second half)

Round 4 wrote that admit-then-build and build-then-admit are behaviour-equivalent once teardown is
confined, and reported M5 surviving as expected. Under the **fallback** that equivalence needed
stating more carefully, and here it is: on the terminal fallback path build-then-admit leaves an
unpaired real frame, and admit-then-build would leave *the same* unpaired real frame — the drain has
no wait any more, so it would find an unbuilt pairing and emit nothing. Restoring admit-then-build
without also restoring the deleted deadline is not a better trade, it is a worse-shaped one. And on
the non-terminal path there is now **no fallback at all**, so the distinction has nowhere left to
matter.

## Mutations — 12 applied, a rebuild between each, 12 discriminated

| # | Mutation | Result |
|---|---|---|
| M1 | the reconnect primitive falls back to the caller after the bound (the round-4 shape) | **caught** — 4 behavioural tests + 1 tripwire; the split-pair test reproduces the P1 on the wire |
| M2 | the coordinator routes the swap through the terminal (falling-back) entry point | caught |
| M3 | `ZitroneApp` asks for the reconnect while still holding `transportLock` | caught (tripwire; see the honesty note above — not a behavioural defect on its own today) |
| M4 | the second terminal wait is unbounded again | caught |
| M5 | a reconnect queued behind terminal teardown still redials | caught |
| M6 | queued transport changes are not coalesced by generation | caught |
| M7 | terminal teardown runs on the caller instead of being dispatched | caught |
| M8 | the terminal fallback is removed — a vault lock can hang without wiping keys | caught |
| M9 | the account-delete path dispatches onto the worker it is already running on | caught |
| M10 | a fourth, UNGUARDED cover call site written `coverTraffic . cover(` | caught |
| M11 | a socket disconnect hidden in a THIRD file, written `disconnect( )` | caught |
| M12 | `emit` swallows the `CancellationException` instead of rethrowing | caught |

**A process note, because it nearly produced a false result.** The first mutation harness was killed
by a timeout mid-run and left one mutation applied; the file was untracked, so `git status` did not
show it, and the *baseline itself* was then red — which makes every mutation report "caught" for free.
The re-run asserts a green baseline before starting, restores in a `finally`, verifies a checksum of
every touched file after each restore, and re-checks the baseline at the end. All twelve results
above come from that run.

## Evidence

`ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`
from `apps/android` → **BUILD SUCCESSFUL, Gradle exit 0**, run three times consecutively (the new
worker tests interrupt threads, so flakiness had to be ruled out rather than assumed). **723 tests
across 78 classes / 3 skipped / 0 failures / 0 errors** (716 → 723). `DecoySendPairingTest` 34 → 41
tests; `DecoyAccountProvisionerTest` 33.

## Residuals, declared rather than claimed away

1. **The terminal fallback**, unchanged in kind and now bounded on both waits: `stop()` waits at most
   `TERMINAL_TEARDOWN_WAIT_MS` (250 ms) per wait for the worker, then runs teardown on the calling
   thread so `UnlockController` can still reach `runtime.close()`. Cost, now measured by a test: an
   **unpaired real frame** on that one teardown — never a lone decoy, never a split pair.
2. **A transport swap now waits for the worker instead of pre-empting it.** With no fallback, a swap
   queued behind a worker blocked (not suspended) is delayed for as long as that block lasts. The
   endpoints are already re-pointed, so only the one live socket lingers; this is a latency residual,
   not a framing one, and it is the price of never splitting a pair. The registration proof-of-work —
   the only multi-second CPU work in the coordinator — runs on `Dispatchers.Default`, not here.
3. **Natural socket death inside the drawn gap** — re-declared (X7). Inherent, and uncorrelated with
   lock, teardown or transport change, which is what distinguishes it from the classes §4.3 closes.
4. **The confinement contract is a contract, not a type.** `DecoySendPairing` still cannot enforce
   that its caller is confined; its lock is kept so a violation degrades rather than corrupts.
5. Unchanged: cross-send `sendLimit` preemption (relay-side), OkHttp write coalescing, R-U3-4's
   uncovered frame on a build refusal.

## What the ruling got right, and the two things it did not

Right: the diagnosis, the severity, the refusal of all four alternatives, and above all the
instruction to fix the **lock boundary** rather than the fallback. That was the correct call and it
worked as specified, with one adjustment (no wait at all — above).

Not right, or at least incomplete:

1. **"can wait for confinement without any fallback"** — waiting is unnecessary and would relocate
   the hang rather than remove it. See above.
2. **The ruling did not name the residual its own construction creates**: removing the fallback means
   the swap is now unbounded in *latency*. That is the right trade — a delayed swap is invisible on
   the wire and a split pair is not — but it is a residual and it is now declared, not discovered in
   round 6.

No merge, no push, no version bump. **1 of 6 fix rounds remains.**
