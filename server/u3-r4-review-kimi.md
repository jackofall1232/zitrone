# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta, U3 — ROUND 4

Reviewer: Kimi (blind reviewer 1 of 2). Branch reviewed: `feat/0.10.0-decoy-u3-pairing` @ `165abb37`
("U3 fix r4: the composed fix — a success signal, and teardown on the send worker"). Base: `a4f118df`.
Date: 2026-07-28. READ-ONLY review; no files were modified during the review.

**Reviewer note on scope:** the prompt's header says U3 round 4 but its SCOPE section lists the U1
files (`VaultState.kt`, `DecoyAuthStore.kt`, …) — the stale parallel-copy again, this time inside
the review prompt itself. Every attack target in the brief (confinement, success signal, wall clock,
quiesce, 250 ms, re-entry, tripwires) is U3 machinery. Reviewed: the whole U3 unit —
`DecoySendPairing.kt` (all 658 lines), the full `MessagingCoordinator.kt` integration, `ZitroneApp`
transport/wiring paths, `UnlockController`, `WsClient`, `DecoySendPairingTest.kt` (all 1,271 lines),
spec R-U3-1…R-U3-5 and §5, and the round-4 fix note (`u3-fix-r4-composed.md`).

## What was verified as SOUND (attacked and could not falsify)

- **The confinement argument holds.** The slice from `ws.sendMessage` to `inFlight.add` contains no
  suspension point: `publishOutgoing`/`publishReceipt` are non-suspend private funs; `cover()`'s
  prologue is two lock acquisitions, `buildCover` (whose `recipient`/`sender` are plain `() -> T`
  lambdas — `ZitroneApp.kt:1724-1733` — and `DecoyEnvelopeBuilder.build` is a non-suspend `fun`,
  `DecoyEnvelopeBuilder.kt:247`), `Pending()`, and the admission. First suspension is `sleep()`
  (`DecoySendPairing.kt:474`). Teardown is dispatched onto `confined` in every path:
  `stop()` → `runTerminalTeardownOnConfinedWorker` (`MessagingCoordinator.kt:786`),
  `deleteAccountAndWipe` → `coverTeardown()` directly on the worker (`:1885`),
  `reconnectTransport` → same helper (`:896`), `onSessionRevoked` → `onForcedLogout` → `lock()` →
  `stop()`.
- **The success signal gates cover at all three sites** (`:1210`, `:1422`, `:1574`), and both tails
  return `true` only from the `ws.sendMessage` branch (`:409-413`, `:439-441`). Tails cannot
  suspend — compiler-enforced.
- **The wall clock is genuinely gone** from `DecoySendPairing`: no deadline, wait loop, condition
  variable, or resolved flag; the drain is a straight loop over a register of built pairings.
- **Re-entry is handled:** `coverTeardown()` runs at `:1885`, strictly before `onConfirmed()` at
  `:1890`; the `@Volatile terminalTeardownDone` skip at `:786` prevents worker self-dispatch
  deadlock; `lockIf`'s identity check protects a successor session.
- **The 250 ms trade is right for `stop()`** and the bound is defensible: the fallback is
  terminal-safe because `transportInvalid` refuses late admissions; `ZitroneApp.kt:961-967`
  confirms `runtime.close()` runs in a `finally` immediately after `coordinator.stop()`, so an
  unbounded wait would indeed gamble the key wipe. 250 ms against `UnlockController`'s 2,000 ms
  session-drain bound is proportionate.
- **W5 fix verified:** `ensureProvisioning` holds the teardown lock across check→CAS→assign
  (`DecoySendPairing.kt:615-634`); `stop()` cancels under the same lock (`:490-491`).
- **M5 survivor is honestly reported** and consistent with the code: once the register only holds
  built pairings, admit-then-build and build-then-admit are behaviour-equivalent.

## FINDINGS

---

### Finding 1 — P2 — the quiesce fallback re-opens the split-pair class, undeclared

*(Severity note: the signal class was ruled P1 in round 3; the two stacked preconditions are why
this is rated P2 — the adjudicator may rule otherwise.)*

**File:line:** `MessagingCoordinator.kt:895-896` → `:860-876`; `DecoySendPairing.kt:508-521`,
`:460-481`

`reconnectTransport` reuses `runTerminalTeardownOnConfinedWorker`, whose 250 ms fallback runs the
lambda **on the calling thread**. For `stop()` that fallback is safe — `transportInvalid = true`
refuses any late admission. For `quiesce` it is not: `quiesce` deliberately leaves
`transportInvalid` false and the register open. Concrete interleave:

1. User toggles Tor. `applyTransportLocked` (holding `transportLock`) calls `reconnectTransport`,
   dispatches quiesce onto `confined`, waits 250 ms.
2. The worker is continuously busy >250 ms (slow disk, a flush backlog — the kdoc itself concedes
   "a worker blocked, not suspended, for that long"). Timeout fires; the calling thread's CAS wins.
3. At that moment a send coroutine on the worker is **inside its publish→admit slice**:
   `ws.sendMessage` has succeeded **on the old socket**, and the thread is OS-descheduled in
   `buildCover` (a vault read — millisecond-scale). No coroutine suspension is needed for this
   interleave; the "uninterruptible slice" argument only holds against teardown *on the worker*,
   and the fallback has just taken teardown off it.
4. Calling thread: `drainLocked()` (register empty — nothing to emit), `swapTransport()` →
   `ws.disconnect()` + `connect()` (**new TLS connection**).
5. Worker resumes: admission succeeds (register open, `transportInvalid` false), sleeps 5–50 ms,
   `finish()` emits the cover frame **on the new socket**.

Result: two identical-length frames straddling a TLS teardown/reconnect, correlated with the user
changing their anonymity transport — verbatim the signal the third lens ruled P1 in round 3. The
spec's round-4 residual ("`MessagingCoordinator.stop()` blocks on the confined worker…") names
`stop()` only; the quiesce half of the same helper is undeclared anywhere, and the fix note's
residual list likewise covers only `stop()`.

**Why the tests do not catch it:** no test in the suite instantiates `MessagingCoordinator` at all.
Both behavioural confinement tests build their *own* `Executors.newSingleThreadExecutor()`; the
production dispatch is pinned only by source-string tripwires; and the fallback branch
(`:872-875`) is never executed by anything.

---

### Finding 2 — P3 — the second `done.await()` is unbounded

**File:line:** `MessagingCoordinator.kt:875` —
`if (ran.compareAndSet(false, true)) terminal() else done.await()`

The second wait is **unbounded**, in the function whose entire stated rationale is that an
unbounded wait is the worst outcome ("a vault lock that can hang and never wipe its keys is worse
than any framing defect"). If the worker claims `ran` at the 250 ms boundary and `terminal()` then
wedges — it can't today (drain/disconnect/connect are all non-blocking; verified
`WsClient.disconnect`/`connect` are async), but nothing pins that property, and the lock
acquisition in `DecoySendPairing.stop` makes it one future refactor away — `stop()` hangs forever
while holding both `transportLock` (`ZitroneApp.kt:962`) and `UnlockController`'s monitor, and
`runtime.close()` never runs. The bounded-then-unbounded structure silently reintroduces the exact
failure the bound exists to prevent; a second bounded wait with a logged giveaway would not.

**Why the tests do not catch it:** the function is only string-matched by the dispatch tripwire;
the branch is never executed.

---

### Finding 3 — P3 — spec residual struck without replacement (natural socket death mid-gap)

**File:line:** `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md` — R-U3-1 round-3 residual, struck by
`165abb37`

The struck round-3 text also carried the sentence accepting "the socket dies between the two
writes… already accepted for ordinary network loss." That acceptance was struck along with the
teardown residual and **not replaced**. The behaviour it covered is still live: a natural socket
death during the drawn gap makes `finish()` → `emit` → `send()` return false and the cover frame
is silently dropped (`DecoySendPairing.kt:585-593`) — a lone real frame. That case is inherent and
the code can't do better, but the spec no longer names it; a reader of the current spec would
conclude every unpaired-frame class is closed. Re-declare it (or move it to §2.4) rather than
letting it fall out of the record by accident of an adjacent strike-through.

**Why the tests do not catch it:** the dead-socket-on-cover test (`DecoySendPairingTest.kt:429`)
asserts the real send is unaffected — the correct R-U3-1 property — but nothing asserts the
resulting lone frame is a *declared* residual.

---

### Finding 4 — P3 — test-count claim does not match the tree

**File:line:** `l00prite/.l00prite/reviews/decoy-0.10.0/u3-fix-r4-composed.md:101`; spec §5 U3 row

"`DecoySendPairingTest` is 35 of them" — the file contains **34** `@Test` methods
(`grep -c '@Test'` = 34). Trivial in itself, but this is the document whose mutation accounting
("13 applied, 12 discriminated") other reviewers calibrate against, and the 28→35 delta was cited
in the review prompt as evidence of growth.

---

### Finding 5 — P3 — tripwires: stricter than round 3, but three evasions survive

**File:line:** `DecoySendPairingTest.kt:1102-1147` (call-site), `:1049-1098` (disconnect),
`:1151-1208` (dispatch)

The rewrites are genuinely better — comment stripping, brace-walking, both disconnect owners, the
dependence-not-adjacency regex. But:

- **Call-site:** the `total` count regex requires exact token adjacency `coverTraffic.cover(`. An
  unguarded fourth call site written `coverTraffic . cover(` (legal Kotlin; survives whitespace
  normalisation with spaces around the dot) matches *neither* `total` nor `guarded` → suite green
  with a live unguarded site.
- **Disconnect:** the scan is a literal `indexOf("disconnect()")`; `disconnect( )` is invisible.
  More importantly, only two files are read — a disconnect moved into any third file (a
  `TransportSwapper` helper) is invisible, so the fix note's claim "a helper that hides the
  disconnect fails" is only true while the helper lives in those two files.
- **Dispatch:** the `assertEquals(1, …)` on `coverTraffic.stop {` counts only the coordinator file.
  A second `coverTraffic.stop {` added in `ZitroneApp.kt` (or anywhere else) passes every
  tripwire — the disconnect tripwire even whitelists that exact opener.

**Why the tests do not catch it:** they *are* the tests; each evasion exploits the gap between the
property named and the syntax pinned.

---

### Finding 6 — P3 — tests whose named property is carried by the harness, not the code

**File:line:** `DecoySendPairingTest.kt:453`, `:247`, `:572`, `:598`, `:518`, `:827`/`:882`

- **`:453`** `a CancellationException out of the cover frame cannot skip the real publish` —
  **vacuous.** `published++` happens before `cover()` is entered; the assertion passes for *any*
  `cover()` behaviour, including swallowing the `CancellationException` — the one thing `emit`'s
  rethrow contract forbids and the test never asserts.
- **`:247`** — the harness records `Real` *before* calling `cover()`, so
  `realGoneWhenCalled.all { it }` is tautological; a decoy-first `DecoySendPairing` passes it.
  Only "3 collaborators were called" is live.
- **`:572`** — the test itself spends `Real` before calling `cover`; passes even if cover never
  emits at all. It asserts the harness's statement order, not within-pair permit priority.
- **`:598`** — both launches record their real frame before entering `cover()`, so the asserted
  order holds by launch order even with a mutex across the pair; the comment "restoring any lock
  around the pair fails this" is not true of the assertion as written.
- **`:518`** — counts only suspensions routed through the injected `sleep` seam; a future direct
  `delay(...)` inside `DecoySendPairing` bypasses the counter.
- **`:827` / `:882`** — both pin `DecoySendPairing`-internal properties *given* serialised
  teardown, on a harness-built worker. The slow-build test does discriminate a cross-thread
  teardown (300 ms `Thread.sleep` build forces the interleave), so the fix note's test-side-
  mutation claim stands; but neither test can see whether *production* teardown is confined —
  that rests entirely on the string tripwires above.

---

## Judgments on the declared residuals and the questions posed

- **Is the 250 ms trade right?** Yes for `stop()`, for the reason given — and the fix note is
  honest that it bounds waiting, not work. It becomes wrong only by reuse: the same helper backs
  `quiesce`, where the fallback is not terminal-safe (finding 1), and the second unbounded `await`
  undercuts the stated rationale (finding 2).
- **`quiesce` against a socket reconnecting mid-drain:** `updateTransport` before the drain is
  harmless (a volatile re-point of future dials only, `WsClient.kt:154-156`); the drain emits on
  the still-live old connection; `WsClient.disconnect` is non-blocking so the drain can't deadlock
  the swap. The ghost-socket race (quiesce queued behind `coverTeardown` re-dialling a torn-down
  session) is **not** reachable — `transportLock` serialises `stopSession` against
  `applyTransportLocked`, and the `connectionState != DISCONNECTED` guard (`ZitroneApp.kt:1516-1518`)
  is always evaluated after any teardown completes. Not reported as a finding.
- **Do the tripwires now pin what they claim?** Mostly — the call-site tripwire now pins dependence
  (verified: it catches M1, M2, M11 as claimed), the dispatch tripwire's brittleness errs toward
  failing correct refactors rather than passing broken ones (safe direction), and the disconnect
  tripwire's carve-out is genuinely gone. The three evasions in finding 5 are the residue.
- **§4.3/§5 against the code:** no normative contradiction found. §5's un-struck round-3 sentence
  "drains the pairings it admitted before running it" remains literally true under build-then-admit.
  The genuine staleness is the struck network-loss acceptance (finding 3) and the test count
  (finding 4).

VERDICT: FINDINGS (0 P1, 1 P2, 5 P3)
