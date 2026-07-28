# U3 FIX ROUND 3 of 6 — cover traffic made strictly SUBORDINATE, at both ends (2026-07-27)

Round 2's simplification held where it was attacked. What did not hold were two claims of the same
shape, and the adjudication named it exactly: **cover traffic placed where it can precede or outlive
the real send.** V1 put cover work before the handoff; V2 let cover work outlive the socket it needs.

Round 2's own words were the tell — *"a process can only die at a suspension point."* A coroutine may
only **suspend** at a suspension point. The OS can kill the process at **any instruction**, which is
what §1's threat model assumes. The claim was false, and it concealed a real loss path.

## V1 (P1) — the seam can no longer be handed a real send at all

**Was:** `coverTraffic.paired(envelope) { …publish tail… }`. `publish()` ran first *inside* `paired`,
but reaching that statement cost an interface dispatch, a captured lambda and entry into a coroutine
state machine — all of it between the durable ratchet advance and `ws.sendMessage`.

**Is:** the coordinator publishes, then calls the seam.

```
publishOutgoing(envelope, conversation.contactId, messageId)   // or publishReceipt(…)
coverTraffic.cover(envelope)
```

`CoverTraffic.paired(cover, publish)` is gone; the interface is `suspend fun cover(real)`. There is no
parameter it could run. `C` — the set of instructions cover traffic adds between the durability
barrier and the socket — contains **one** non-suspending call, `publishOutgoing` / `publishReceipt`
itself, and that call is not cover's: it exists so the compiler still rejects a suspension inside the
`contactExists → ws.sendMessage` tail (D2c). Handing the tail back to the call sites as inline code
would have made `C` literally empty **and silently retired that enforcement** — the deletion class
this unit has now been caught by twice. It stayed, as a member of the send path that would remain
correct and necessary if cover traffic were deleted tomorrow. That is the third lens's own carve-out:
*code independently required for the real send is not added "because cover traffic was attempted."*

**Declared residual, and it is forced.** Between `ws.sendMessage` returning and the pairing
registering itself with teardown there are a handful of instructions (see V2). Closing that would mean
registering *before* the publish — cover work in front of the handoff, and a lock a real send could
queue on — which R-U3-1 forbids absolutely. V1 and V2 are jointly unsatisfiable at exactly this seam;
what is left has no suspension, no I/O and no allocation of consequence, and is the same class as
"the socket dies between the two writes", already accepted for ordinary network loss.

## V2 (P1) — teardown OWNS the pairings it admitted, and owns the disconnect

**Was:** `ws.disconnect()` at `MessagingCoordinator.kt:669`, `coverTraffic.stop()` at `:675`, and a
`stop()` that cancelled only the provisioning job. Every vault lock landing inside a drawn gap put a
lone real frame and then a TLS close on the wire.

The third lens ruled that reordering the two statements is **not sufficient**, because step 3 —
*cancel, complete or drain pairings already admitted* — requires ownership `stop()` did not have.
So the fix is ownership, and the ordering is expressed as a dependency rather than a convention:

- `CoverTraffic.stop(invalidateTransport: () -> Unit)` **runs the disconnect itself**, last. The
  coordinator no longer has a `ws.disconnect()` it could put in the wrong place — both remaining call
  sites (`stop`, and the account-delete teardown) pass it in.
- `DecoySendPairing` keeps a register of **admitted pairings**. `cover()` admits before it builds;
  `stop()` takes the same lock, emits every admitted pairing's frame **immediately, gapless, while
  the socket is still live**, waits (bounded, `DRAIN_TIMEOUT_MS = 100`) for any pairing still
  *building*, and only then invalidates.
- **Membership of the register is the right to emit** — whoever removes a pending emits it, under the
  lock. There is no `emitted` flag; the removal is the token. This is load-bearing in one real window:
  the drain releases the lock while it waits, and a pairing it has already emitted can wake inside
  that window with the transport still valid.
- The wait can be bounded safely because **`buildCover` is non-suspending**: from admission to the
  built frame reaching the drain there is no suspension point, so the wait can only ever stand behind
  CPU work, never I/O. The timeout is a backstop for an unscheduled thread, not an expected path.

`stop()`'s invalidation is in a `finally`: a teardown that fails to invalidate the transport is a
session that outlives its own lock.

**Declared residual (new, not in the adjudication):** `ZitroneApp.applyTransportLocked` also
disconnects — on a user-initiated transport change (Tor toggle) — and does not drain. It reconnects
immediately and is not lock/teardown-correlated, so it is a narrower observable than the one fixed
here, but it is a second `disconnect` the register does not own. Named in §4.3 R-U3-5 rather than left
implicit. Draining it would need a *non-terminal* quiesce (the current stop is terminal by design),
which is a new lifecycle state on a security-sensitive surface and is not worth it in this round.

## V3 (P2) — the wiring latch bounds CONCURRENT jobs, not attempts

`provisioningStarted` was a once-per-session CAS. A durable back-off from a prior 429 makes
`provisionIfNeeded` return **without burning `Gate.attempted`** — a local refusal is one *check*, not
the one *attempt* — so the single call landed inside the window and was never made again. Cover
traffic stayed off for the whole session even after the window expired, silently retiring a property
U1 pins explicitly.

The latch is now released when the job completes (`finally`, with a `CoroutineStart.LAZY` launch so
`provisionJob` is assigned before the body can run). The registration budget is untouched because it
was never this latch's job — `DecoyAccountProvisioner`'s runtime-scoped `Gate.attempted` is the guard
that protects the shared worldwide bucket, exactly as the class kdoc already claimed. A
`transportInvalid` check keeps a released latch from starting an attempt after teardown.

## V4 (P3, 14th recurrence) — §5

U1: "deliberately UNWIRED" struck (U3 constructs it), the obsolete 640–643 B figure demoted to a
superseded measurement behind the 700 B / 636–646 B U2 R2 numbers, "four review rounds" → six with the
third-lens tiebreak, "merge pending" → **merged**. U2: UNWIRED struck, "review round 3 not yet
dispatched" struck, merged. U3's own row rewritten around the round-3 gate. §4.3 gains the third
lens's binding clarification of "materially" under R-U3-1 and the four-step teardown lifecycle under
R-U3-5, each with its declared residual.

## Tests — 20 → 28 in the pairing suite, +1 cross-unit in the provisioner suite

The two "why tests missed it" notes are addressed at the root: the suite now drives **a socket that
really dies** (`DyingSocket` — `WsClient.send` is `webSocket?.send(frame) ?: false`) and **the real
teardown entry point**, not a permanently successful fake.

- `teardown drains an in-flight pairing BEFORE the socket dies`
- `teardown waits for a pairing whose cover frame is still being BUILT` (real threads: `stop()` blocks,
  and the point is that it blocks for this)
- `a pairing the drain already emitted does not emit again when it wakes` (the one window where
  exactly-once can actually be violated)
- `the drain is bounded — a pairing that never resolves cannot hold the socket open`
- `a pairing admitted after teardown emits nothing at all` — including *no cover work at all*
- `a back-off that expires mid-session still gets its attempt`, and `provisioning is never started
  after teardown`
- `the cover-traffic seam cannot be handed a real send to run` — reflection on the interface, because
  reintroducing a `publish: () -> Unit` would compile and pass every behavioural test in the file
- `the drawn gap is the only suspension point, and it is after the handoff` — what survives of the
  old process-death test once its false premise is dropped
- **Two call-site tripwires**, because `MessagingCoordinator` cannot be constructed off-device and the
  call site is where the round-2 defect actually lived: every `ws.disconnect()` in the coordinator is
  inside `coverTraffic.stop {`, and every `coverTraffic.cover(` is immediately preceded by a publish
  tail. They read the source. That is unusual and deliberate — it is the only reach this suite has
  into the ordering that the two P1s were about.
- **Cross-unit (`DecoyAccountProvisionerTest`):** `THE WIRED PATH gets that attempt too` — a real
  `VaultRuntime` carrying a real 429 deferral, a real `DecoyAccountProvisioner`, and the real send
  seam. U1's version of this property makes two direct calls; this one goes through the pairing,
  which is the gap V3 lived in.

### Mutations — 11 run, 11 discriminated, 0 survivors, rebuilt between each

| # | Mutation | Killed by |
|---|---|---|
| M1 | `ws.disconnect()` before `coverTraffic.stop {}` (round-2 order) | the disconnect tripwire |
| M2 | `stop()` drains nothing (round-2 behaviour) | drain + mid-build tests |
| M3 | drain what is ready, never wait for a pairing still building | mid-build test |
| M4 | invalidate the transport before the drain | drain + mid-build tests |
| M5 | restore the once-per-session provisioning latch | pairing + cross-unit back-off tests |
| M6 | add `cover(real, publish)` back to the interface | the reflection tripwire |
| M7 | unbounded drain wait | the bounded-drain test |
| M8 | drop the register-membership guard in `finish` | the double-emit test |
| M9 | admit pairings after teardown | the post-teardown test's "no cover work" assertion |
| M10 | a `delay(1)` inside `publishOutgoing` | **the compiler** — which is the D2c enforcement V1 had to keep |
| M11 | `coverTraffic.cover(envelope)` above the publish tail | the call-site-order tripwire |

M8 and M9 **survived their first form** and are recorded as such: the original `emitted` flag was
unreachable-as-false (`transportInvalid` dominated it), and the admission gate's emission effect was
already covered by `finish`. Both were fixed rather than excused — `emitted` was deleted in favour of
register membership, which *is* reachable in the drain's wait window, and the post-teardown test now
asserts that a locked vault does no cover work at all. Both then discriminated.

### Evidence

`ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`
from `apps/android` → **BUILD SUCCESSFUL, Gradle exit 0**, **712 tests / 3 skipped / 0 failures /
0 errors** (701 → 712), APK produced. Pairing suite 28, provisioner suite 33.

## What the adjudication gets wrong, stated rather than absorbed

**The third lens's four-step lifecycle is not jointly satisfiable with its own V1 ruling at step 1.**
"Stop admitting new real sends" is the coordinator's to do, and the only way to make it atomic with
"drain pairings already admitted" is to register the pairing before — or under the same lock as — the
real publish. V1 forbids exactly that. Steps 2, 3 and 4 are satisfiable and are implemented; step 1 is
satisfied for *pairings* (the admission gate) but cannot be for *sends*, and the residual is the few
instructions named above. Round 2's window was 5–50 ms wide and caught **every** pairing that was
mid-gap; this one is not a window teardown can be relied on to hit. That is a reduction of about four
orders of magnitude, not an elimination, and it is written down as such in §4.3 rather than claimed
away.

**On "structural" — the word that was misused last round.** Two things here are structural in the
sense that no runtime check enforces them and no code path can reach the bad state:

1. **The seam cannot run a real send**, because it has no parameter that could hold one. Undoing it
   requires changing an interface signature, which is what the reflection test pins.
2. **The transport cannot be invalidated without the drain having run**, because `ws.disconnect()` is
   reachable in `MessagingCoordinator` only as the argument to `CoverTraffic.stop`, and `stop` runs it
   in a `finally` after the drain, under the same lock the admission gate uses.

Everything else here is **guarded**, and is described that way: the drain's completeness is guarded by
a bounded wait, exactly-once is guarded by register membership under a lock, and the ordering at the
three call sites is guarded by a source tripwire because the class cannot be host-constructed.

No merge, no push, no version bump. 3 of 6 fix rounds remain.
