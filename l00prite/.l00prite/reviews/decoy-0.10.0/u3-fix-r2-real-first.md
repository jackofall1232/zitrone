# U3 fix round 2 — implementing the real-frame-first ruling. A SIMPLIFICATION round.

**Branch** `feat/0.10.0-decoy-u3-pairing`. **Round 2 of a hard cap of 6.** Ruling implemented:
§4.3 R-U3-2 as amended in `81761dfb` — the real frame always goes first, random ordering conceded.

The round-1 stop said *"under real-first most findings cease to exist rather than being repaired."*
This round acted on that literally. **`DecoySendPairing` lost a mutex, a plan record, an order bit,
two latching booleans, a nested `finally`, two kdoc sections and every branch it had.** The whole of
R-U3-1 is now paid for by one statement.

## The mechanism, in full

```kotlin
override suspend fun paired(cover: MessageEnvelope, publish: () -> Unit) {
    publish()                                   // ← the entire R-U3-1 argument
    val decoy = coverFor(cover) ?: return
    try { sleep(gapMs()) } finally { emit(decoy) }
}
```

`publish()` is the first statement, outside every `try`, with **no suspension point in front of it
and no condition guarding it**. Everything else is downstream of a frame that is already on the
socket. Entering a suspend function is not itself a suspension point, so even an already-cancelled
caller gets its publish — there is nothing before it that could check for cancellation.

## Per finding: impossible, repaired, or gone

| # | Verdict | Argument |
|---|---|---|
| **U3-A** process death in the gap | **IMPOSSIBLE BY CONSTRUCTION** | A process can only die *at a suspension point*. There is exactly one suspension point in the class — the drawn gap — and the real frame is on the socket before it exists. Stated as a property and tested exhaustively rather than by sampling (see the test below), because "at every suspension point after the durable barrier the real frame has already gone" is a claim about a set of size one. |
| **U3-B** `deleteContact` interleave | **IMPOSSIBLE BY CONSTRUCTION** | There is no suspension between the durable flush and the `contactExists → ws.sendMessage` tail to interleave *in*. The byte sequence the confined worker executes is the pre-U3 one. |
| **U3-C** self-preemption half | **IMPOSSIBLE BY CONSTRUCTION** | The real frame is enqueued first, so within a pair the cover frame can only take a permit the real one did not need. **Cross-send preemption is untouched and deliberately undefended** — out of scope by instruction, relay-side, and no sound client-side defence exists (`sendLimit` is a server constant the relay never communicates). |
| **U3-D** CE rethrow skips the publish | **IMPOSSIBLE BY CONSTRUCTION** (was repaired in `694782c3`) | `emit`'s `CancellationException` rethrow now runs strictly after the publish. Its round-1 repair — the nested unconditional `finally` — was **deleted**, because it existed only to survive the decoy-first branch. The regression test is kept. |
| **U3-E** timing asymmetry | **GONE, not repaired** | The finding was that the observable interval contained different work depending on the *hidden order*. There is no hidden order. Re-derived: the interval is now `decoy build + drawn gap` on every send, identical in composition for all of them. Build time could in principle track envelope class — but frame length gives that away directly, so it discloses nothing new. |
| **U3-F** the 5 ms floor | **REPAIRED, and demoted with a derivation** | The finding is correct and survives the ruling: `WsClient.sendMessage` hands the frame to OkHttp's async writer queue, so the floor separates two *calls*, not two socket writes, and it cannot flush. What the finding did **not** derive is the cost. Now that the order is fixed, a coalesced pair presents as one record of exactly twice the frame length — and that reading says precisely what two separate frames say ("one covered send happened here"), naming no conversation. The equal-length property is about the two halves being indistinguishable *from each other*; a coalesced pair has no halves to tell apart. **So the residual is cosmetic, not a leak.** The floor is kept as best-effort and the kdoc now says "best effort" where it used to say "keeps them apart". |
| **U3-G** cancel-while-waiting emits outside the lock | **GONE — there is no lock** | See below. |
| **U3-H** the `GAP_MAX_MS` bound is per-hop, not total | **GONE, and replaced by a stronger claim** | With no lock and no pre-publish suspension, cover traffic adds **zero** delay to a real send — not a small bound, none. Pinned by a test that asserts two concurrent sends both publish with **no virtual time advanced at all**; restoring any lock around the pair fails it. |
| **U3-I** untested R-U3-1 edges | **THE POINT OF THE ROUND** | All four named gaps now have discriminating tests. See below. |

## Whether the pairing lock survives: NO. Argued from its callers.

The `window` mutex had exactly two justifications in its own kdoc, and the ruling removed both:

1. *A real send queued behind a decoy-first pairing would overtake it on the wire.* There is no
   decoy-first pairing. Real frames leave in exactly the order the coordinator issues them, with no
   suspension in front of any of them — the pre-U3 property **restored**, not reconstructed.
2. *Only the decoy-first branch would be interleaving-free, so "a foreign frame appeared between the
   pair" would be evidence for real-first.* There is one branch. Nothing to leak.

**No third caller.** It was taken by `paired` and by nothing else; it protected an ordering
invariant that no longer exists. Deleted, along with the entire "Lock order" section — the class now
takes no lock at all (it *calls* `recipient`/`sender`, which take theirs internally, holding
nothing).

Deleting it also deleted a claim the class could not honestly make: *"a concurrent send waits at
most `GAP_MAX_MS`"* (false under multiple waiters — U3-H).

## What else was deleted

- `Plan` (the record carrying the order bit) → `coverFor()` returns a `MessageEnvelope?`.
- `random.nextBoolean()` and every `if (plan.decoyFirst)` branch — three of them.
- The `realDone`/`decoyDone` latching booleans and the `real()`/`decoy()` local functions: with one
  call site each, a latch has nothing to prevent.
- The nested `finally` (U3-D's round-1 repair) → one flat `finally` that emits the cover frame.
- The kdoc's "Why the pair is emitted under a lock" and "Lock order" sections; the order half of the
  gap-distribution rationale; the `alwaysDecoyFirst()` test helper.

## What was deliberately KEPT, and why each is still load-bearing

- **The `finally` around the gap.** Not a decoy-first artefact. Cancellation (vault lock, teardown,
  backgrounding) is frequent, and letting it drop the cover frame would leave the real frame
  **unpaired** — a *marked* frame, which is exactly what R-U3-3 forbids. The gap is lost to
  cancellation; the pair is not.
- **`coverFor`'s catch-all.** Its justification INVERTED and that is worth recording: it used to stop
  a cover-side throw from aborting a real send. It now stops a cover-side throw from propagating into
  `MessagingCoordinator`'s `runCatching`, which would `markFailed` a message that was **already
  delivered**. Same code, a different defect underneath it.
- **`SecureRandom` by type**, with a rewritten argument — the order bit it used to protect is gone,
  but the gap is *directly observable on the wire* and is now the only drawn quantity. A
  `java.util.Random` here leaks a 48-bit LCG state to anyone who measures a handful of gaps, turning
  the gap into a stable device fingerprint that links pairs, links sessions, and — one instance per
  live vault session on one device — could link **two vaults' traffic**. That is a
  plausible-deniability break, not a traffic-analysis nuisance.
- **`publish` as a non-suspending `() -> Unit`.** Still the compiler-enforced statement of D2c at
  three call sites.

## The tests — 20, from 15, and 5 of them are new

New, all four U3-I gaps:

- `at the only suspension point the real frame is already on the wire` (**U3-A / process death**) —
  asserts through the `sleep` seam, which *is* the suspension point, so the property is exhaustive
  rather than sampled.
- `a deleteContact queued on the confined worker cannot interleave before the publish tail`
  (**U3-B / confinement + concurrent delete**) — both coroutines on ONE `StandardTestDispatcher`,
  the delete queued behind a running send, exactly as `Dispatchers.IO.limitedParallelism(1)` does it.
- `with one send permit left the REAL frame takes it, never the cover frame` (**U3-C / limiter
  boundary**) — a socket that accepts exactly one more frame.
- `an in-flight pairing neither delays nor reorders a concurrent real send` (**U3-H**) — replaces
  `a pairing in flight delays a concurrent send but never overtakes it`, whose premise the ruling
  deleted. Asserts the opposite property: no virtual time passes between the two real frames.
- `no cover-side code runs before the real publish` — the ruling's own words asserted. **This is the
  test for the quiet regression**: hoisting the envelope *build* above the publish introduces no
  suspension, so the confinement test would not notice, but it puts cover-side work, latency and
  throws back in front of a real send.

Replaced: `the frame order is uniformly random and independent across many sends` →
`the REAL frame always goes first - every send, every envelope class`. **One decoy-first send is now
a defect, not a sample**, so the test is absolute and runs on the production generator — the order
must not be a function of any draw, so no seed may be able to make it come out right.

Also added: a **lag-1 autocorrelation** assertion on the gap. The old suite could not tell a
per-send draw from one draw reused, and the round's own mutation M6b (each drawn gap reused for the
next send) passes support, bound and mean.

## Mutation evidence — 15 run, 15 discriminated, 0 survivors

Every one rebuilt (`:app:testDebugUnitTest` after a source edit, source restored after each).

| Mutation | Killed by |
|---|---|
| M1 decoy-first | 8 tests |
| M2 gap moved ahead of the publish (round-1's own layout) | process death, no-cover-code-first, in-flight, deleteContact |
| M3 restore the mutex around the pair | in-flight (**only** — that is the U3-H test doing its job) |
| M4 drop the `finally` | cancellation-in-gap |
| M5 fixed gap | gap distribution, default-generator |
| M6 gap drawn once per instance | gap distribution (support) |
| M6b each drawn gap reused for the next send | gap distribution — **`r=0.512`, the autocorrelation assertion**, confirmed by reading the failure message |
| M7 `coverFor` rethrows | the three uncovered-send tests |
| M8 `emit` rethrows everything | throwing socket |
| M9 provisioning latch removed | provision-once |
| M10 `stop()` no-op | stop cancels provisioning |
| M11 pair only TTL-bearing envelopes | every-envelope-class, equal-length, real-first |
| M12 `CoverTraffic.NONE` drops the tail | NONE |
| M13 the cover frame IS the real envelope | equal-length / carries-nothing |
| M14 dead socket treated as an escaping error | dead socket, throwing socket, limiter |

**All 20 tests are killed by at least one mutation.** No test in the file is inert.

## Build/test evidence

```
ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
BUILD SUCCESSFUL — Gradle exit 0
701 tests / 3 skipped / 0 failures / 0 errors     (697 → 701)
DecoySendPairingTest: 20 tests / 0 failures       (15 → 20)
```

## Two gaps the RULING left, both closed here (doc only, no decision taken)

1. The ruling says the traded property is *"recorded as a residual in §2.4"* — **the ruling commit
   did not add it.** Added, with the second-order consequence stated so it is not rediscovered:
   because the order is fixed, pairs from concurrent sends may now interleave on the wire (nothing
   serialises them any more), which reveals nothing since the halves are associable by length and
   which one is real is now public.
2. **§5's U3 row still demanded "ordering is uniformly random — pinned by a statistical test"** — the
   unit's own merge gate, contradicting the ruling that governs it. Struck and replaced.

## Still open after this round

- **U3-C cross-send**, relay-side, grouped for the CX23 trip. Not defended against, by instruction.
- **`onServerError` empty** — separate live defect, not this round's.
- **U3-J** synthetic-account delete: closed as a merge gate per the maintainer's 2026-07-27 ruling
  (`37fcdc06`); one doc line owed to U6, no code.
- Review round 2 of U3 not dispatched. **4 of 6 fix rounds remain.**
