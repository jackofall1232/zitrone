# U3 — ROUND 4 adjudication (Grok + Kimi paired-blind, Codex tie-break)

Grok: `FINDINGS (1 P1, 2 P2, 2 P3)` · Kimi (completed, external instance): `FINDINGS (0 P1, 1 P2, 5 P3)`
Codex tie-break: **P1**, plus a construction resolving the dilemma.
**Adjudicated: 1 P1, 3 P2, 5 P3.**

## FIRST CONVERGENCE IN SEVEN ROUNDS

Every prior round the two reviewers were **disjoint on the top finding**. This round they landed on
the **same defect independently** — the quiesce fallback re-opening the split-pair class. That is the
signal the surface is being exhausted, per the calibration rule (`failures.md`): convergence *with
severity falling* is exhaustion; convergence with findings rising would be anchoring.

Kimi's mechanism was sharper than Grok's and is the version of record: **no coroutine suspension is
needed for the interleave — the "uninterruptible slice" argument only holds against teardown running
*on the worker*, and the 250 ms fallback has just taken teardown off it.** The fallback does not
merely have an unjustified bound; **it structurally defeats the confinement argument the entire
round-4 fix rests on, precisely when it fires.**

Kimi rated it P2 while explicitly deferring severity to the adjudicator — correct reviewer behaviour,
and the reason this went to tie-break rather than to my judgment.

## X1 — P1 (Codex tie-break, over Grok P1 / Kimi P2-deferred)

`reconnectTransport` reuses `runTerminalTeardownOnConfinedWorker`. For `stop()` the caller-thread
fallback is safe — it sets `transportInvalid`, refusing late admission. **`quiesce` deliberately
leaves `transportInvalid` false and the register open**, so the fallback drains an empty register,
swaps the socket, and the worker then emits the cover frame on the *new* connection while its real
frame went out on the old. Split pair across a TLS boundary, correlated with a transport change —
verbatim the class ruled P1 in round 3.

**Stacked preconditions affect likelihood, not severity** (standing precedent). **And it is
undeclared** — the residual note covers `stop()` only, while `reconnectTransport` reuses the same
primitive.

## X2 — the dilemma, and the ruling that resolves it

The obvious remedy (lengthen or drop the fallback for `quiesce`) reinstates a **verified deadlock**.
Codex confirmed the cycle with a five-step citation chain, including a link neither I nor Kimi had
found — `MainActivity.kt:1283`:

1. `applyTransport` takes `transportLock`; `applyTransportLocked` calls blocking `reconnectTransport`
   **without releasing it** (`ZitroneApp.kt:1505,1526`).
2. `reconnectTransport` waits on work queued on `confined`.
3. `deleteAccountAndWipe` already runs on that worker (`MessagingCoordinator.kt:1811`).
4. Its `onConfirmed` calls `lockIf` (`MainActivity.kt:1283`).
5. `lockIf → stopSession` takes `transportLock` (`UnlockController.kt:119`, `ZitroneApp.kt:961`).

**RULING — fix the lock boundary, not the fallback:**

> Under `transportLock`, resolve and install the new endpoints and **capture the current
> `SessionContainer`. Release `transportLock`.** Then invoke a reconnect **confined to that captured
> coordinator, with no caller-thread fallback.** On the worker, skip the swap if terminal teardown has
> begun or completed; ideally coalesce queued transport changes **by generation** so only the newest
> requested state reconnects.

This keeps the decisive property — **drain and socket swap execute atomically with respect to every
publish/admit slice** — while removing the lock edge that creates the deadlock. Cost: a small
lifecycle/generation guard around a captured session. **That is the correct cost to pay.**

**Four alternatives were considered and each fails**, which is why the lock boundary is the answer:
- `quiesce` refusing admission → converts the split pair into an **unpaired real frame**; still marked.
- Socket-identity-aware emission → prevents cross-boundary cover but **still leaves the real frame
  unpaired** unless disconnect waits for admission.
- Removing only the non-terminal fallback → **reinstates the verified deadlock**.
- Permanently setting `transportInvalid` during a live swap → **disables subsequent pairing**;
  resetting it creates another unsafe transition.

## Also confirmed

- **X3 (P2, Grok):** the dispatch tripwire does not pin that the transport swap is confined.
- **X4 (P2, Grok):** the declared-residual path is untested; the admit-then-build mutation note
  understates what still depends on order under fallback.
- **X5 (P2, Kimi):** **no test instantiates `MessagingCoordinator` at all.** Both "confinement"
  behavioural tests build their **own** `Executors.newSingleThreadExecutor()`, production dispatch is
  pinned only by source-string tripwires, and **the fallback branch is never executed by anything.**
  The tests named for confinement do not test production confinement.
- **X6–X9 (P3, Kimi):** second `done.await()` unbounded; a spec residual struck without replacement
  (natural socket death mid-gap); test-count claim doesn't match the tree; three tripwire evasions
  survive.

## Explicitly cleared (attacked, could not be falsified)

Kimi verified and did **not** report: the quiesce-mid-drain reconnect race (`transportLock` serialises
`stopSession` against `applyTransportLocked`, and the `connectionState != DISCONNECTED` guard is
always evaluated after teardown completes); `updateTransport` before the drain (a volatile re-point of
future dials only); and the auto-reconnect-mid-gap split (backoff ≥1 s versus a 5–50 ms gap — **not
reachable**). A reviewer reasoning its way to a non-finding and declining to file it is the discipline
working.
