moon: asking kimi-k3...
## RULING

**Position A wins. The four mechanisms are P1 violations of the requirements as written, and the feature is not shippable while R-U3-1 and R-U3-3 remain absolute.**

Position B correctly identifies that some of the failures are least-bad engineering tradeoffs and that some residual transport failure is unavoidable. That explains why the requirements are infeasible; it does not make the implementation compliant. A residual can be documented, tested, and preferable to the alternatives while still violating an absolute requirement.

### 1. A declared, tested residual cannot satisfy an ABSOLUTE requirement

No.

An absolute requirement with a supremacy clause admits no “documented residual” exception. A test asserting the forbidden result is useful characterization evidence, but it is not a conformance test for the current requirement. Until the specification is amended, that test encodes the contradiction; it does not resolve it.

The four mechanisms also do not all have the shape Position B claims:

| Mechanism | Requirement violated | Actual failure shape |
|---|---|---|
| OkHttp queue saturation | **R-U3-1** | A later real send returns `false` where the no-cover implementation would have accepted it. This is not merely an unpaired real frame. |
| Terminal-teardown fallback | **R-U3-3**, and potentially R-U3-1 for later sends queued behind the blocked worker | An accepted real frame is left unpaired; a long cover build can also delay subsequent sends on the confined worker. |
| Shared relay budget | **R-U3-1** | A real send is rejected earlier than it would be without cover. With a 100-frame budget, 50 real sends plus 50 decoys exhaust it; the 51st real send fails. |
| Socket death during the gap | **R-U3-3** | The real frame is emitted and the cover frame is not. |

The correct disposition is therefore:

1. **Change the requirements explicitly**, with product/security signoff and measurable residual budgets; or
2. **Do not ship the feature.**

A third possibility—redesigning the relay and transport—could remove some causes, such as separate relay accounting for cover frames or connection-generation-safe pairing. It cannot make a network incapable of dying between two writes. Therefore the current absolute wording cannot be preserved intact.

The case-2 fallback may still be the correct least-bad mitigation: an unpaired frame is preferable to a vault lock that never reaches its key wipe. But “less bad than the alternative” is not “compliant.” The chosen priority order must be reflected in the requirement.

### 2. The R-U3-3 rationale does turn against the feature

**Yes, under the rationale as written.**

Without cover, a transport failure produces an ordinary singleton frame. With cover, the protocol establishes an expectation of pairs; a singleton in the covered size class becomes a protocol-specific anomaly and identifies the real frame. The feature therefore creates a marked class exactly where pairing fails.

That does not mathematically prove that every nonzero residual rate makes the feature net-negative in every threat model. A sufficiently rare, non-inducible failure might be an acceptable expected-utility trade. But the current rationale is categorical—“intermittent cover is worse than none”—and supplies no utility model under which rare intermittency is acceptable. It cannot simultaneously:

- declare one unpaired frame among a hundred worse than no cover; and
- accept an unavoidable nonzero unpaired rate.

Either the categorical rationale must be replaced with a quantified risk model, or the feature fails by its own justification.

### 3. There is no universal numeric net-negative rate; shipping without one is not defensible

Under the current absolute wording, the threshold is effectively **zero**. Since zero is not attainable on a real network, the requirement and feature are incompatible.

Under a relaxed, risk-based requirement, the threshold depends on quantities not present in the design:

- probability that an unpaired frame is identifiable;
- probability and cost of a split pair;
- cost of cover-induced real-send failure;
- number of sends an observer can collect;
- whether failures are random, user-correlated, relay-correlated, or attacker-inducible;
- the privacy benefit obtained from successful pairs.

For a simple detection criterion, if `p` is the marked-frame probability per accepted real send, then over `N` sends:

```text
P(at least one marked frame) = 1 - (1 - p)^N
```

If the product chose a target of at most probability `q` of one marked frame over a reference window of `N` sends, the required rate would be:

```text
p_max = 1 - (1 - q)^(1/N)
```

For illustration only, `q = 1%` over `N = 1000` sends requires approximately:

```text
p_max ≈ 1.005 × 10^-5
```

That is about one marked frame per 99,500 sends. This is not a recommended threshold; it demonstrates why “rare” cannot be left unquantified.

Shipping generally available privacy code without measuring this is not defensible. The prohibition on decoy diagnostics does not prevent pre-release measurement. The release gate should include a matched cover/no-cover control and fault injection for:

- a stalled OkHttp writer at queue boundaries;
- relay-budget exhaustion;
- worker stalls before and while holding the pairing lock;
- socket death at every point in the 5–50 ms gap;
- terminal lock during builds, sleeps, drains, and reconnects;
- transport toggles racing terminal teardown.

An internal, disabled-by-default experiment could ship without a final product threshold; a generally available feature carrying the current absolute claims should not.

### 4. The mechanisms differ materially

They differ in requirement breached, observable shape, correlation, exploitability, and available mitigation.

- **Queue and budget failures are availability defects.** They cause future real sends to fail. They violate R-U3-1 directly and are not accurately described as “unpaired real frames.”
- **Socket death is a pairing-integrity failure.** It produces the classic unpaired real frame when the first frame escaped and the second did not.
- **The terminal fallback is both a pairing failure and a scheduling hazard.** It is correlated with lock/teardown and can leave later sends queued behind cover construction.
- **A split pair is potentially worse than an unpaired real frame.** An unpaired frame marks one event; a split pair links the same event across two connection generations and can bind it to an independently visible transport change.
- **A lone decoy is a different failure.** It exposes cover activity without a corresponding real event. It may be less directly harmful to one sender than an unpaired real frame, but it undermines the uniformity of the cover stream.

Inducibility does not change compliance under the current absolute requirements: inducible and natural failures are both P1. It does change the risk class under a relaxed requirement. An attacker-inducible failure is materially worse because the observer can manufacture repeated marked samples, convert a rare residual into a deterministic oracle, and choose its timing. Random, non-inducible failure may be priced statistically; attacker-controllable failure should receive a much stricter budget or remain prohibited.

Position B’s blanket statement that every residual is an unpaired real frame is therefore factually incomplete: mechanisms 1 and 3 are real-send availability failures.

### 5. Replacement requirement wording

The following preserves the strong structural guarantees while honestly relaxing the impossible parts. The two `T_*` values must be assigned before release; no defensible numeric value can be derived from the supplied code alone.

> **R-U3-1 — Real-send precedence and availability residual**
>
> No cover-traffic instruction may execute between the real send’s durability barrier and its transport handoff. Cover traffic must not reorder an accepted real frame, change its recorded outcome after handoff, or reduce its durability.
>
> Exhaustion of a pre-existing shared transport queue or relay budget is an explicitly declared availability residual for subsequent real sends. The implementation shall measure `p_avail`, the excess real-send failure rate relative to a matched no-cover control, under the release fault-injection matrix. The 95% upper confidence bound for `p_avail` shall not exceed `T_avail`.
>
> Any other cover-induced block, failure, material delay, reorder, or durability loss is release-blocking.

> **R-U3-3 — Pair integrity and marked-frame residual**
>
> For every real envelope accepted by the transport, the implementation shall attempt at most one equal-length cover envelope on the same connection generation.
>
> A decoy must never be emitted without a corresponding accepted real envelope. A cover frame must never be emitted on a different connection generation; if same-generation emission is impossible, the cover frame must be suppressed.
>
> An accepted real frame without a same-generation cover frame is a declared residual only when caused by transport failure, declared terminal-teardown fallback, or shared-resource exhaustion. The implementation shall measure `p_unpaired` under the release fault-injection matrix. Its 95% upper confidence bound shall not exceed `T_pair`.
>
> An attacker-inducible excess above `T_pair`, or deterministic correlation with lock, teardown, or transport changes outside an explicitly declared terminal fallback, is release-blocking. If the measured rate exceeds `T_pair`, cover traffic must be disabled uniformly before further sends rather than continue intermittently.

Until `T_avail` and `T_pair` are specified and measured, this relaxation is incomplete and the feature remains non-shippable.

## FINDINGS

1. **P1 — `CoverTrafficWorker.kt`, `runTerminalConfined`; `DecoySendPairing.kt`, `stop` and `quiesce` — the terminal fallback can still hang indefinitely on the pairing lock.**

   `runTerminalConfined` bounds only the wait before invoking fallback teardown:

   ```kotlin
   if (done.await(terminalWaitMs, TimeUnit.MILLISECONDS)) return
   if (!runTerminalHere(teardown)) done.await(terminalWaitMs, TimeUnit.MILLISECONDS)
   ```

   Once `runTerminalHere` wins the CAS, `teardown()` runs with no bound. `DecoySendPairing.stop` begins with:

   ```kotlin
   override fun stop(invalidateTransport: () -> Unit) = teardown.withLock {
   ```

   A reachable interleaving is:

   1. A transport swap runs on the worker.
   2. `quiesce` acquires `DecoySendPairing.teardown`.
   3. The worker wedges or is starved while holding that lock, including inside `swapTransport`.
   4. Vault lock calls `runTerminalConfined`.
   5. The first 250 ms wait expires.
   6. The fallback calls `stop` and blocks indefinitely acquiring the same lock.

   The vault-close/key-wipe path then hangs—the precise outcome the fallback is documented to prevent. Existing fallback coverage exercises a worker blocked before or outside the pairing lock; it does not wedge `quiesce` while holding the lock, so the timeout test passes while the actual teardown call remains unbounded.

2. **P1 — `CoverTrafficWorker.kt`, `requestReconnect`; `DecoySendPairing.kt`, `quiesce` — terminal teardown can race an already admitted reconnect and redial a dead session.**

   `requestReconnect` checks terminal state before invoking the reconnect:

   ```kotlin
   if (isTerminal) return@launch
   if (mine != requested.get()) return@launch
   reconnect()
   ```

   `quiesce` has no terminal-state check before:

   ```kotlin
   inFlight.clear()
   swapTransport()
   ```

   A concrete interleaving is:

   1. The worker starts a reconnect and observes `isTerminal == false`.
   2. It is preempted for more than 250 ms before calling `reconnect()`.
   3. A vault lock cannot get worker time, so `runTerminalConfined` takes the fallback.
   4. The fallback runs `stop`, invalidates the transport, and marks teardown terminal.
   5. The worker resumes and calls `reconnect()` anyway.
   6. `quiesce` drains an empty register and invokes `swapTransport`, redialling or replacing transport for a terminal session.

   This breaks the guarantee that “a reconnect queued behind a teardown is dropped rather than redialling a dead session.” Existing tests set `terminal` before the reconnect job starts; virtual-time tests do not force preemption between the terminal check and `reconnect()`.

3. **P1 — `WsClient.kt`, `send`; `DecoySendPairing.kt`, `emit` — cover can consume the final outbound-queue capacity needed by the next real send.**

   `WsClient.send` returns OkHttp’s enqueue result:

   ```kotlin
   private fun send(frame: JSONObject): Boolean =
       webSocket?.send(frame.toString()) ?: false
   ```

   With a stalled writer and two queue slots available:

   1. Real frame N is accepted, leaving one slot.
   2. Its decoy consumes the final slot.
   3. Real frame N+1 calls `sendMessage` and receives `false`.
   4. Without cover, frame N+1 would have been accepted.

   That is a direct R-U3-1 failure, not merely a pairing residual. Tests using a fake `send: (MessageEnvelope) -> Boolean` do not model OkHttp’s bounded asynchronous writer queue, and normal tests do not stall the writer at the capacity boundary.

4. **P1 — `DecoySendPairing.kt`, `cover` and class documentation — cover doubles consumption of the shared relay budget.**

   The implementation emits one additional `message.send` for every real envelope:

   ```kotlin
   emit(pending.decoy)
   ```

   Against a nominal budget of 100 sends per minute:

   1. Fifty real sends produce fifty decoys.
   2. The account reaches 100 sends.
   3. The 51st real send is rejected.
   4. Without cover, 100 real sends would fit the nominal budget.

   This is a deterministic, reachable R-U3-1 availability failure. Unit tests do not consume relay-side budget because the relay is absent; the client documentation also says the server does not communicate the constant needed for a sound headroom policy.

5. **P1 — `CoverTrafficWorker.kt`, `runTerminalConfined`; `DecoySendPairing.kt`, `cover` — the 250 ms fallback can invalidate a transport under a mid-build pairing.**

   Exact sequence:

   1. The real frame is handed to `WsClient`.
   2. `cover()` enters `buildCover(real)` on the confined worker.
   3. The build occupies the worker for more than 250 ms.
   4. Terminal teardown times out and runs on the caller.
   5. `stop()` sets `transportInvalid`.
   6. The worker finishes the build and executes:

      ```kotlin
      if (transportInvalid) false else inFlight.add(pending)
      ```

   7. Admission returns `false`; the real frame remains unpaired.

   This violates absolute R-U3-3. The shipped test described in the prompt does not miss the behavior; it asserts the forbidden result as an accepted residual, thereby encoding the conflict rather than resolving it.

6. **P1 — `DecoySendPairing.kt`, `cover`, `finish`, and `emit`; `WsClient.kt`, `send` — natural socket death during the drawn gap drops the second frame.**

   Exact sequence:

   1. The real frame is accepted at `t=0`.
   2. A 20 ms gap is drawn.
   3. The TLS socket dies at `t=10`.
   4. `finish` runs at `t=20`.
   5. `send(decoy)` returns `false`.
   6. `emit` deliberately swallows the result.

   If the first frame reached the network, the observer sees an unpaired real frame. A unit test can simulate `false`, but treating that as an accepted result does not make it conform to absolute R-U3-3; no client-side test can make a real network incapable of failing between the two writes.

7. **P3 — `DecoySendPairing.kt`, `buildCover` — the promised defect signal for builder failure is absent.**

   The documentation says reaching the builder-failure branch is “a defect to report, not a case to swallow quietly,” but the implementation is:

   ```kotlin
   } catch (t: Throwable) {
       null
   }
   ```

   A builder failure therefore produces an uncovered real frame with no counter, tripwire, diagnostic, or other observable signal. If the next envelope succeeds, this is also an intermittent R-U3-3 failure. A test that injects a throw and asserts only “returns null/does not throw” passes while the reportability property named by the documentation remains unimplemented. Severity is P3 because no reachable production input triggering the builder throw was established from the supplied context.

8. **P3 — `DecoySendPairing.kt`, constructor `sender` and `buildCover` — the documented “only condition” claim is false, creating a possible intermittent predicate.**

   The class documentation says the only per-send condition is `recipient()`, but the build path also tests the sender:

   ```kotlin
   sender()?.let { from -> builder.build(from, syntheticAccountId, real) }
   ```

   The constructor explicitly permits a null sender and says the account can be re-linked under a live session. If the callback sequence is null for send N and non-null for send N+1, send N is unpaired and send N+1 is paired—exactly the intermittent shape R-U3-3 rejects. Tests using a fixed sender callback cannot observe this. Severity is P3 pending confirmation that `MessagingCoordinator` can actually supply null after an accepted real send.

## MISSING CONTEXT

- **`MessagingCoordinator.kt` — the concrete `sender` lambda and send-failure handling.** Needed to determine whether `sender() == null` is reachable after an accepted real send, whether it can flip during a live session, and whether a `false` return is retried or surfaced. Defect classes: hidden intermittent pairing predicate and cover-induced real-send availability loss.
- **`ZitroneApp.kt` — the exact lambda passed to `CoverTrafficWorker.requestReconnect`.** Needed to confirm precisely what `swapTransport`/`reconnect` does after the terminal TOCTOU and whether it calls `WsClient.connect`. Defect classes: redial after terminal teardown and cross-generation pairing.
- **`UnlockController.kt` — ordering among `MessagingCoordinator.stop`, session-scope cancellation, transport lock release, and vault-runtime close.** Needed to verify how long key material remains live if fallback teardown blocks or a reconnect resumes afterward. Defect class: key-wipe delay/race.
- **All `WsClient.connect`, `updateTransport`, and `disconnect` call sites.** Needed to prove that no authentication refresh, manual reconnect, or other socket replacement can bypass `CoverTraffic.quiesce` and replace the current `webSocket` during the 5–50 ms gap. Defect class: split pair across connection generations.
- **`DecoyEnvelopeBuilder.kt` and `DecoyAccountProvisioner.kt`.** Needed to identify concrete builder-throw inputs and every possible transition of the synthetic-account predicate. Defect classes: intermittent unpaired frames and silent degradation without a tripwire.
- **The relevant unit/integration tests for `CoverTrafficWorker` and `DecoySendPairing`.** Needed to verify whether the two lock/teardown races are merely untested or intentionally accepted. Defect class: a test passing while the terminal-teardown property it names is broken.

VERDICT: FINDINGS (6 P1, 0 P2, 2 P3)
