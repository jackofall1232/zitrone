I'll do a read-only adversarial review of the cover-traffic unit: load protocol context, then trace the named files and call sites for R-U3-1 / R-U3-3 failure modes.Unit files are under `apps/android`. Reading the core cover-traffic sources and prior-round review notes.Tracing call sites next: MessagingCoordinator, ZitroneApp transport paths, stopSession/lockIf, and tests.Reading critical call sites and prior-round findings in parallel.Evaluating Codex’s four P1s against declared residuals, then hunting undeclared failure modes.Checking one last interleaving: `isTerminal` early-return vs in-flight teardown, and whether `onSessionRevoked` can disconnect before drain.# FINDINGS

None. After constructing the interleavings that produced P1s in rounds 3–5, the remaining surfaces either fail closed into **declared residuals** with acceptable trades, or are **inherent 2×-volume** effects already written down (cross-send `sendLimit`; same shape as OkHttp’s 16 MiB writer queue). Codex r6’s four P1s re-file those residuals / inherent costs rather than new reachability.

---

# CONFIRM-OR-REFUTE

1. **CONFIRM** — Live swaps only via `ZitroneApp.applyTransport` → `reconnectTransport` → `CoverTrafficWorker.requestReconnect` → `scope.launch(confined)` → `coverTraffic.quiesce`; no `await` / `runTerminalHere` on that path (`CoverTrafficWorker.kt:181–187`, `MessagingCoordinator.kt:851–852`, `ZitroneApp.kt:1524–1532`).

2. **REFUTE (only on declared terminal fallback)** — Confined path: `Dispatchers.IO.limitedParallelism(1)` plus no suspend from `publishOutgoing`/`publishReceipt` through `buildCover`→admit, so teardown cannot enter that slice. Fallback path: worker blocked mid-build > 250 ms → caller `runTerminalHere` → `transportInvalid` → admit fails → unpaired real (`CoverTrafficWorker.kt:152–155`, residual test `DecoySendPairingTest.kt:1380`).

3. **CONFIRM for deliberate swap; natural death is unpaired, not split** — Quiesce drains then `disconnect`/`connect` on the worker. Auto-reconnect uses ≥ 1 s backoff (`WsClient.kt:337–340`, `BASE_BACKOFF_MS=1000`) vs gap ≤ 50 ms, so `finish` hits a dead/old socket (`send` → false), not a new TLS session. Auth re-link is multi-second HTTP before `ws.connect`.

4. **CONFIRM** — Membership in `inFlight` is the emit right; `stop`/`quiesce` drain under `teardown` before invalidate/swap; `finish` no-ops if already removed. Bound can only skip a **not-yet-admitted** mid-build pair (fallback residual), not an admitted one.

5. **CONFIRM for the cited cycle** — `applyTransport` installs under `transportLock`, **releases**, then `requestReconnect` without waiting (`ZitroneApp.kt:1524–1532`). `stopSession` still holds `transportLock` across `runTerminalConfined`’s wait, but the worker no longer needs that lock to complete swap/teardown; delete→`lockIf` during wipe is gated by `beginTerminalWipe` / `deleteInFlight`.

6. **CONFIRM start-after-teardown; registration budget is per-runtime + durable account** — `ensureProvisioning` holds `teardown` across check→CAS→`provisionJob`→`start`; `stop` cancels under the same lock (`DecoySendPairing.kt:615–633`, `484–505`). `Gate.attempted` is one relay attempt per live `VaultRuntime`; `hasAccount` blocks re-register once credentials exist (orphans after failed commit are the accepted U1 outcome).

7. **CONFIRM** — `CoverTraffic.cover(MessageEnvelope)` only; interface pin test forbids a publish/`Function0` parameter (`DecoySendPairingTest.kt:514–555`). Call sites: `if (publish…) coverTraffic.cover(envelope)`.

8. **CONFIRM** — Pairing/builder take no logger; emit/provision fail silent; durable decoy state is vault `TAG_DECOY` only; `VaultState.wipe` clears it on close; process-local gates/locks are weak maps with no vault content.

---

# HYPOTHESES NOT IN THE PRIOR LIST

| Area checked | Result |
|---|---|
| **OkHttp `RealWebSocket` queue (16 MiB)** — cover fills queue so next real `send` returns false | Same *class* as declared cross-send `sendLimit` preemption; needs a stalled writer + multi‑MB backlog; not a new ordering bug |
| **`onAuthExpired` / `bootstrapLoop` → `ws.connect` without `quiesce`** while mid-gap | Re-auth is multi-second; gap ≤ 50 ms → cover fails on dead socket (unpaired), reconnect does not win the race |
| **`onSessionRevoked` skips direct `coverTeardown`** | Still reaches drain via `onForcedLogout` → `lockIf` → `stop`; socket still open when the revoke frame is read |
| **`runTerminalConfined` `if (isTerminal) return` without joining in-flight teardown** | `terminal` flips before `teardown()` body; concurrent `stop` can return mid-drain — drain only `send`s already-built frames, no vault read; `runtime.close` does not corrupt pairing state |
| **`limitedParallelism(1)` vs true single-thread executor** | Mutual exclusion of non-suspended slices holds; tests use a named single thread, production uses an IO view — behavioral for confinement, not thread identity |
| **Stale `SessionContainer` after unlock of successor** | Terminal latch + cancelled session scope drop queued reconnects |
| **`quiesce` not cancelling provision** | Correct for non-terminal; session continues |
| **Tripwire `bodyOf` / normalised lock-boundary** | Correctly extracts `synchronized { applyTransportLocked }` without `reconnectTransport` |
| **Inverted assertion *message*** at split-pair test (`"after the swap"` vs generation `1` = pre-swap) | Check value is right (both on gen 1); message is wrong — docs only, not a product defect |

---

# MISSING CONTEXT

- **OkHttp sources jar** not cached; confirmed `MAX_QUEUE_SIZE = 16777216` via bytecode only — enough for the queue-class residual, not a line-level send-failure policy review.
- **Relay live `sendLimit` enforcement** not executed end-to-end; treated as the already-declared §6 residual.
- **No full `MessagingCoordinator` JVM construction** (same constraint as prior rounds); dispatch behaviour is covered via `CoverTrafficWorker` tests + source wiring tripwires.

---

# DECLARED RESIDUALS — judgment

1. **Transport swap unbounded behind a blocked worker** — **Acceptable.** Caller does not wait; endpoints already re-pointed; cost is delayed redial (invisible on the wire), not a split pair. Preferable to any caller-thread fallback on an open register.

2. **Natural socket death mid-gap** — **Acceptable as unpaired**, not as split. Spec §4.3 is accurate (`emit` → false). Review-prompt wording “can still **split**” is overstated given ≥ 1 s reconnect backoff vs ≤ 50 ms gap. Uncorrelated with lock / transport toggle.

3. **`stop()` bounded wait because runtime closes on return** — **Acceptable.** Key wipe / no-remanence outranks one unpaired real on the rare blocked-worker path; residual is measured (unpaired real only; never lone decoy / never split). Absolute R-U3-1 is preserved on that path (real already handed off).

---

**Soundness argument for CLEAN:** Real publish is strictly before any cover instruction (R-U3-1 durability window = pre-U3). Deliberate TLS replacement always drains on the same serialisation point as publish→admit. The only client-driven unpaired class left is the terminal fallback under a blocked worker — declared, tested, and the lesser evil vs a hung lock without wipe. Inherent 2× shared-budget effects are out of client-side sound policy.

VERDICT: CLEAN
