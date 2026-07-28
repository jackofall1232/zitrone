I'll conduct a read-only adversarial review of Unit U3 (round 4): first loading the protocol and design docs, then verifying the confinement, cover-gating, quiesce, teardown, and tripwire claims against source.U3 is the pairing unit on this branch. Loading the round-4 prompt and the composed-fix notes, then the core implementation.Core U3 pairing, coordinator, and tripwire surfaces next — verifying suspension points, cover gates, and teardown paths.Verified the confinement slice, all three cover gates, quiesce, the 250 ms fallback, re-entry, and the rewritten tripwires against source. Findings below.

---

## Attack surface verification (claims that hold)

### 1. Confinement / no suspension in the publish→admit slice — **holds under pure confinement**

- `publishOutgoing` / `publishReceipt` are non-`suspend` `private fun`s; only `return true` path is `ws.sendMessage` (`MessagingCoordinator.kt:399–446`).
- `ws.sendMessage` → `webSocket?.send(...)` — non-suspending (`WsClient.kt:176–212`).
- Call sites: `if (publish…) coverTraffic.cover(envelope)` at lines 1210, 1422, 1574.
- `DecoySendPairing.cover` (`DecoySendPairing.kt:449–481`): until `sleep(gapMs())` there is no suspension — only `teardown.withLock`, `buildCover` (plain `fun`), `inFlight.add`.
- Terminal teardown: `stop()` → `runTerminalTeardownOnConfinedWorker(::coverTeardown)` (`MessagingCoordinator.kt:786`); delete path calls `coverTeardown()` directly on the confined worker (`1885`) — correct (self-dispatch would stall).

**Under pure confined execution**, teardown is strictly before or after the whole publish→build→admit slice, never inside it.

### 2. Success signal gates cover — **holds**

All three sites depend on Boolean publish tails; both tails return `true` only from the `ws.sendMessage` branch. Contact-deleted / socket-refused paths return `false` and do not cover.

### 3. Wall clock gone — **holds inside `DecoySendPairing`**

No `DRAIN_TIMEOUT`, condition variable, or `resolved` flag. Build-then-admit; register only holds built `Pending`s; `drainLocked` never waits.

### 4. Re-entry `terminalTeardownDone` — **holds for the skip**

`coverTeardown` sets the flag then stops cover (`816–819`). Re-entry from `deleteAccountAndWipe → onConfirmed → lockIf → stop()` skips dispatch (`786`). Ordering of `acceptingSends = false` before teardown on both paths is correct.

### 5. Residual trade for **terminal** `stop()` — **defensible as stated**

Waiting ≤250 ms for the worker, then falling back so `UnlockController` can still `runtime.close()` and wipe keys, is the right priority (keys > framing). The residual *for terminal stop* is an unpaired real frame on that rare path.

---

## Findings

### F1 — P1 — Quiesce reuses the 250 ms calling-thread fallback → split pair after a real handoff

**Files:**  
`MessagingCoordinator.kt:860–876` (`runTerminalTeardownOnConfinedWorker`), `895–896` (`reconnectTransport`), `2340` (`TEARDOWN_QUIESCE_MS = 250L`);  
`DecoySendPairing.kt:449–467` (build-then-admit, no lock across build), `508–520` (`quiesce` does **not** set `transportInvalid`);  
`ZitroneApp.kt:1516–1529` (`applyTransportLocked` → `reconnectTransport`).

**Concrete failure sequence**

1. Confined worker: `ws.sendMessage(real)` succeeds on socket **A**, then enters `buildCover` (non-suspending; holds the worker; **does not** hold `teardown` and **has not** admitted).
2. Transport changes (I2P promote/demote, Tor toggle): `applyTransportLocked` holds `transportLock` and calls `reconnectTransport`.
3. Worker stays in `buildCover` for **>250 ms** (suite’s own slow-build test sleeps **300 ms** in `recipient()`; production can stall on `SecureRandom` / `Curve.generateKeyPair` / vault read under load — `DecoyEnvelopeBuilder` uses both).
4. `done.await(250 ms)` fails → calling thread wins `ran` CAS and runs `coverTraffic.quiesce(swap)`:
   - `inFlight` empty (pairing not admitted) → drain is a no-op  
   - `swapTransport`: disconnect **A**, connect **B**  
   - `transportInvalid` stays **false**
5. Worker finishes build, admits, sleeps, `emit`s cover on socket **B**.

**Outcome:** real frame on **A**, cover on **B** — a **split pair** across a TLS boundary, correlated with a transport change. Same class as round-3 W3 (ruled P1: stronger than a missing cover frame).

**Why this is not the declared residual**

The residual note (`MessagingCoordinator.kt:852–858`, spec §4.3) justifies the bound for **terminal** `stop()` because `runtime.close()` / key wipe must not hang. `reconnectTransport` is non-terminal: there is **no** vault wipe racing the drain. A longer wait (or no calling-thread fallback) only delays a Tor/I2P apply. Applying the key-wipe timeout to quiesce reintroduces the exact split-pair defect the quiesce path was built to close.

**Why tests miss it**

- Slow-build test (`DecoySendPairingTest` ~882–928) enqueues `stop` on the **same** single worker — never exercises `TEARDOWN_QUIESCE_MS` fallback.
- Quiesce behavioural tests call `pairing.quiesce` directly under test control — no 250 ms timeout, no mid-build off-worker race.
- No test models `reconnectTransport` + slow `buildCover`.

---

### F2 — P2 — Dispatch tripwire does not pin that the transport swap is confined

**File:** `DecoySendPairingTest.kt:1151–1208` (dispatch tripwire), `1049–1098` (disconnect tripwire).

**Concrete failure (mutation)**

Change:

```kotlin
fun reconnectTransport(swapTransport: () -> Unit) =
    runTerminalTeardownOnConfinedWorker { coverTraffic.quiesce(swapTransport) }
```

to:

```kotlin
fun reconnectTransport(swapTransport: () -> Unit) =
    coverTraffic.quiesce(swapTransport)
```

**What still passes**

| Guard | Why still green |
|---|---|
| Disconnect tripwire | Still sees `disconnect()` under `coordinator.reconnectTransport {` |
| Dispatch tripwire | Still asserts `stop()` uses the helper and that the helper launches on `confined`; **never mentions `reconnectTransport`** |
| Interface / call-site tripwires | Untouched |
| Behavioural quiesce tests | Call `DecoySendPairing.quiesce` directly |

**Wrong outcome:** mid-gap Tor/I2P apply drains (or races) on the **calling** thread again — W3 restored with every “stricter” tripwire green.

**Why tests miss it:** the dispatch tripwire pins only terminal `stop` / `deleteAccountAndWipe` shape, not the new W3 owner.

---

### F3 — P2 — Declared residual path is untested; admit-then-build mutation note understates what still depends on order under fallback

**Files:** `MessagingCoordinator.kt:860–876`; `DecoySendPairing.kt:449–467`; fix note (M5 survivor).

**Concrete point**

Fix note: admit-then-build vs build-then-admit is behaviour-equivalent **once teardown is confined**, so M5 staying green is expected.

That equivalence **fails** on the calling-thread fallback path:

| Order | Timeout mid-build after real handoff |
|---|---|
| Build-then-admit (current) | Not in register → drain empty → stop: unpaired; **quiesce: split (F1)** |
| Admit-then-build (round 3) | In register unbuilt → drain had to wait (deleted deadline) or abandon |

So: confinement makes order equivalent **only when the helper does not fall back**. The suite never runs teardown through `runTerminalTeardownOnConfinedWorker` with a forced timeout, so it cannot discriminate either the residual or F1.

**Why tests miss it:** confinement tests use a raw single-thread executor + direct `pairing.stop`, not the production helper + 250 ms bound.

---

### F4 — P3 — Unbounded second wait contradicts the residual’s own key-wipe rationale

**File:** `MessagingCoordinator.kt:872–875`

```kotlin
if (done.await(TEARDOWN_QUIESCE_MS, TimeUnit.MILLISECONDS)) return
if (ran.compareAndSet(false, true)) terminal() else done.await() // unbounded
```

**Concrete failure**

If the worker has claimed `ran` and `terminal()` never returns (stuck emit/disconnect path, future regression), `stopSession` holds `transportLock` and never reaches `runtime.close()` — the exact “lock hangs without wiping keys” outcome the 250 ms bound exists to prevent.

Normal drain/disconnect is fast; this is a consistency defect in the residual design, not a demonstrated production hang today.

**Why tests miss it:** no test of the CAS-loss branch or of a hanging terminal.

---

### F5 — P3 — Tripwires improved, still not what they claim for the full story

| Tripwire | Pins | Still walks past |
|---|---|---|
| Interface surface | Full `CoverTraffic` method set | Correct for re-adding a publish lambda shape |
| Call site | `if (publish…) cover` + Boolean + single `return true` from `ws.sendMessage` | Vacuous `\|\| true` caught; **no behavioural** test that a failed publish emits zero decoys (harness `record()` always covers) |
| Disconnect | Both coordinator + ZitroneApp, brace-walked | Good for W3 carve-out removal |
| Dispatch | Terminal stop/delete shape | **F2**: reconnect path unpinned |

Judgment: stricter and more honest than round 3, but still **guards that look complete while missing the new quiesce serialisation owner**.

---

## Claims reviewed and not raised as defects

| Claim | Result |
|---|---|
| No suspension between `sendMessage` and `inFlight.add` | True (first suspend is `sleep`) |
| All three cover sites gated on handoff | True |
| Delete path on-worker, not via self-dispatch | True |
| `terminalTeardownDone` skip on re-entry | True |
| Wall clock removed from pairing class | True |
| `ensureProvisioning` check→CAS→assign under `teardown` | True |
| Terminal 250 ms vs key wipe | Trade is right; residual unpaired is acceptable **for stop only** |
| R-U3-1 (no cover work before handoff) | Holds; K window not enlarged by cover |
| R-U3-3 uniform failure | Predicate is durable `recipient()`; not `canSend()` / capacity flap |

---

## Residual judgment (required item 5)

- **Terminal `stop()`:** waiting ≤250 ms for a free worker, then falling back so keys are wiped, is correct. Framing defect on that rare path is worse than non-wipe, but better than hang.
- **Same bound on `reconnectTransport` / `quiesce`:** **not** defensible. No key wipe; fallback recreates split pairs (F1). Quiesce should not share the key-wipe timeout without a different, security-preserving policy (wait longer, or never fall back off-worker for non-terminal drain).

---

VERDICT: FINDINGS (1 P1, 2 P2, 2 P3)
