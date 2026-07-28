I'll conduct a blind adversarial review of U3 end-to-end: load the protocol and required specs, then verify each structural claim against the actual source and tests.U3 pairing is on this branch — I'll load the required design docs and the pairing/cover implementation next.I won't read the other r3 reviews. Next I'll verify the r3 claims against the pairing implementation and call sites.Examining coordinator publish/teardown paths, `applyTransportLocked`, and the pairing tests next.Checking one more concurrency edge on provisioning vs `stop`, then finalizing findings.# ADVERSARIAL SECURITY REVIEW — Zitrone 0.10.0-beta U3 — ROUND 3

**Reviewer:** blind (no other r3 findings consulted)  
**Tree:** `feat/0.10.0-decoy-u3-pairing` @ `e60a7887`  
**Scope:** whole unit (U1–U3 surfaces + pairing suite), claims verified against source

---

## Claim tests (mechanism-level)

| Claim | Verdict |
|---|---|
| 1. Seam cannot be handed a real send (`cover(real)` only) | **Interface true; “no construction” overstated.** Call-site order is test-guarded, not structural. |
| 2. `publishOutgoing` / `publishReceipt` residual sound | **Sound.** Real-send members; no cover-specific work in the durability→socket window. |
| 3. `ws.disconnect()` only via `stop` in coordinator | **Holds** — two sites, both `coverTraffic.stop { ws.disconnect() }`. |
| 4. Exactly-once under drain wait | **Holds** — register membership is the emit token; double-emit window closed. |
| 5. `buildCover` non-suspending ⇒ bounded wait safe | **Suspend: true. Block/throw: residual.** Timeout still drops cover frames. |
| 6. `applyTransportLocked` residual | **Real, correctly named, acceptably deferred** (see below). |
| 7. Step 1 jointly unsatisfiable with real-first | **Partially unsound** — step 1 for *new* sends is implementable without pre-handoff cover work. |

---

## Findings

### F1 — P2 — `ensureProvisioning` can start a job after `stop()` has already cancelled

**File:line:** `DecoySendPairing.kt:409–437` (`stop`), `526–544` (`ensureProvisioning`)

**Concrete failure:**

1. Unprovisioned vault; real send completes `publishOutgoing` / `ws.sendMessage`.
2. `cover()` admits a `Pending`, enters `buildCover` → `recipient() == null` → `ensureProvisioning()`.
3. `ensureProvisioning` observes `transportInvalid == false` under the lock, then **releases** the lock (`526–528`).
4. Concurrent vault lock runs `stop()`:
   - `provisionJob?.cancel()` sees **null** (`412–413`) — job not assigned yet.
   - Drain waits on the admitted unresolved pending (`421–427`).
5. `ensureProvisioning` continues: CAS, `launch(LAZY)`, `provisionJob = job`, `job.start()` (`529–544`).
6. `buildCover` returns `null`, pending resolves, drain finishes, `transportInvalid = true`, socket dies (`434–436`).
7. **Provisioning job is still running** — never cancelled. It may call `provision()` after session teardown (`stopSession` then `runtime.close()`), spend the shared worldwide registration bucket, and leave an orphaned relay account.

LAZY only ensures the body cannot run *before* assignment relative to the assigning thread. It does **not** close the cancel-before-assign race against `stop()`. There is no second cancel in `stop`’s `finally`.

**R-U3-5 / §6.2a:** a decoy-related job can outlive the session; registration is not confined to a live send session.

**Why tests miss it:**

- `provisioning is never started after teardown` only does `stop()` **then** `record()` — admission is refused, so `ensureProvisioning` never runs.
- `stop cancels the provisioning job` starts the job **before** `stop`, so `provisionJob` is non-null at cancel time.
- No concurrent “first send + lock during `ensureProvisioning`” interleaving test.

---

### F2 — P2 — Call-site order is not structural; tripwires do not pin “C is empty”

**File:line:** `DecoySendPairingTest.kt:465–486` (reflection), `938–958` (call-site order), `MessagingCoordinator.kt:1051–1057` (and attachment/receipt twins)

**Concrete failure (regression constructions that keep the suite green or nearly green):**

**A. Reflection only forbids `kotlin.Function` parameters.**

```kotlin
fun interface RealPublish { fun run() }
suspend fun cover(real: MessageEnvelope, publish: RealPublish) // reintroduces the round-2 shape
```

`Function::isAssignableFrom` is false for a custom SAM / `Runnable`. Behavioural tests still pass if `publish` is invoked first inside `cover`.

**B. Call-site tripwire only checks the previous non-comment line is `publishOutgoing(` / `publishReceipt(`.**

```kotlin
prepareCoverSideEffects()   // cover-specific work back in K∪C
publishOutgoing(envelope, ...)
coverTraffic.cover(envelope)
```

The order tripwire still passes. The reflection tripwire still passes. `DecoySendPairing` unit tests never construct `MessagingCoordinator`.

**C. “Structural” claim in fix-r3 / class kdoc is the same mistake class as round 2.**

The interface having no publish parameter prevents *one* shape. It does not make “cover code cannot precede the handoff” true by construction in the coordinator. That property is held by convention + two brittle source/reflection checks — i.e. **guarded**, not structural.

**Why tests miss it:** the suite pins the two exact round-2 shapes (Function parameter; `cover` line above `publish*`). It does not pin “no cover-dependent instruction in the durability→socket window” as a property of the coordinator body.

**Note:** *current* production call sites are ordered correctly; this is a regression / claim-severity finding, not a live reorder in HEAD.

---

### F3 — P3 — Lifecycle step 1 is implemented last; comments claim the opposite

**File:line:** `DecoySendPairing.kt:414–437`, interface kdoc `70–88`, R-U3-5 four-step lifecycle in the spec

**Concrete mismatch:**

- Spec / interface: (1) stop admitting → (2) stop provisioning → (3) drain → (4) invalidate.
- Code: (2) cancel job → (3) drain while `transportInvalid` is still false → (1)+(4) in `finally`.

During `resolved.awaitNanos` the lock is released, so **new pairings can still be admitted while teardown is already draining** (`379–380` still sees `transportInvalid == false`). Comment at `416–417` (“no pairing admitted from here on”) is false for that window.

**Blast radius:** mostly semantic / documentation. Late admissions are often *helpful* (they can still be drained on a live socket). They can also compete for the global 100 ms deadline. Not shown to create a worse class than the declared post-handoff residual, but the “structural lifecycle” claim is inaccurate.

**Why tests miss it:** no assertion that `transportInvalid` is true for the whole of `stop()`, only that post-`stop()` covers are refused.

---

### F4 — P3 — Claim 7 impossibility argument is only half right

**File:line:** `u3-fix-r3-subordinate.md` residual; `DecoySendPairing.kt:166–175`; spec §4.3 R-U3-5 step 1

**What holds:** making *pairing registration* atomic with `ws.sendMessage` without any cover-side work or a lock on the real path is genuinely hard; the post-handoff pre-admit race for an in-flight publish is real.

**What does not hold:** “stop admitting **new real sends**” is *not* jointly unsatisfiable with real-first. A coordinator `closing` / `acceptingSends` flag set at the start of `MessagingCoordinator.stop()` (before `coverTraffic.stop`) refuses *new* `deliverText` / attachment / receipt work without taking the pairing lock and without putting cover work before the handoff.

Today nothing implements that half of step 1. Concurrent publishes during an in-progress drain still hit a live socket by design (drain needs it). That is a product choice, not a proof of impossibility.

**Why tests miss it:** no test that new sends are refused once teardown begins.

---

### F5 — P3 — Source disconnect tripwire is format-fragile and incomplete

**File:line:** `DecoySendPairingTest.kt:914–934`

**Concrete gaps:**

1. Requires `ws.disconnect()` and `coverTraffic.stop {` on the **same line**. A correct multiline lambda fails the test; a helper `private fun killSocket() { ws.disconnect() }` with `coverTraffic.stop { killSocket() }` also fails (or, if disconnect is only in the helper, the “wired” assertion may fail while order is still correct).
2. Does not see `ZitroneApp.applyTransportLocked` (declared residual) — fine if intentional, but the test name claims “never invalidates outside cover-traffic teardown” for the coordinator only; the process still has a second disconnect owner.

**Why tests miss it:** the tripwire is the test; its own holes are untested.

---

## Claim-by-claim residual judgments

### publishOutgoing / publishReceipt carve-out (claim 2) — **sound**

These methods are the D2c `contactExists → ws.sendMessage` tail: non-suspending, real-path, would remain if cover were deleted. They are not cover-specific instructions inserted into K. Keeping them as methods preserves compiler enforcement that inlining at three call sites would silently retire (mutation M10). **Not the loss window returning under a new name.**

### Exactly-once under drain (claim 4) — **holds**

```458:466:apps/android/app/src/main/java/com/zitrone/app/decoy/DecoySendPairing.kt
    private fun finish(pending: Pending) = teardown.withLock {
        val ours = inFlight.remove(pending)
        ...
        if (ours && decoy != null && !transportInvalid) emit(decoy)
    }
```

Drain removes before emit; finish no-ops if already removed. The wait-window double-emit attack is real and is pinned by `a pairing the drain already emitted does not emit again when it wakes`.

### buildCover / bounded wait (claim 5) — **mostly holds**

`buildCover` is a plain `fun` (no suspend). Wait cannot sit behind coroutine I/O. It *can* sit behind lock contention / crypto / a wedged `recipient` (tests use `Thread.sleep`). On timeout, `inFlight.clear()` drops unresolved pairings without emit → teardown-correlated unpaired real. Accepted as backstop; not a silent infinite stall. **Throwing build:** caught → `null` decoy → real already sent uncovered (R-U3-4).

### applyTransportLocked (claim 6) — **acceptable residual, same shape, smaller trigger set**

```1516:1521:apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt
        if (live != null &&
            live.wsClient.connectionState.value != WsClient.ConnectionState.DISCONNECTED
        ) {
            live.wsClient.disconnect()
            live.apiClient.accessToken?.let(live.wsClient::connect)
        }
```

Mid-gap Tor/I2P toggle: real may already be on the wire; cover is not drained; reconnect is immediate. **Not lock-correlated**, user-initiated, reconnects. Correctly named in §4.3. Building a non-terminal quiesce on this surface mid-round would be scope expansion. **Accept as declared residual** — do not treat as fixed by U3 r3.

### Post-handoff registration residual (claim 7) — **real; “handful of instructions” understates concurrency**

Between `ws.sendMessage` returning and `inFlight.add` under the lock, concurrent `stop()` can finish and refuse admission → unpaired real + TLS close, correlated with lock if the confined worker is descheduled in that window. That is the same *class* as ordinary network loss between two writes, but it is a **thread race**, not only a few sequential bytecodes on one core. Closing it without a pre-handoff lock or cover work remains hard; claiming absolute impossibility for *all* of step 1 is too strong (F4).

---

## Earlier-round invariants (whole unit spot-check)

| Invariant | Spot-check result |
|---|---|
| R-U3-1 absolute / “materially” only on delay | Live path: cover after handoff. No cover-specific instructions inside `publishOutgoing`/`publishReceipt`. F2 is regression risk, not live C≠∅. |
| R-U3-3 uniform failure | Send predicate is synthetic `accountId` (durable; flips once). `canSend()`/capacity correctly *not* used per-envelope. Unpaired on build throw = R-U3-4. Teardown unpaired = residual / F1 context. |
| Register-before-commit (U1) | Still in `DecoyAccountProvisioner`; staging store; fail-closed `accountId` setter. Unchanged by U3 wiring. |
| Presence ≠ readiness | Codec refuses half-credentials; pairing keys on `accountId` (equivalent to provisioned under codec rules). Deferral-only section → `accountId == null` → uncovered + provision attempt. |
| Deniability | No decoy writes to device prefs/diagnostics in decoy package. Fixed-size vault region unchanged by U3. |
| Registration budget | Runtime `Gate.attempted` still the real budget; pairing latch is concurrent-jobs only (r3 V3). **F1 can still spend a registration after teardown.** |
| Parallel-copy / TAG_DECOY field set | `DecoyState` kdoc remains canonical; counter/dead-air fields absent; builder mirrors covered counter. No reintroduction found in this tree. |

---

## Tripwire judgment (summary)

| Tripwire | Pins | Does not pin |
|---|---|---|
| Reflection on `cover` | Re-adding `publish: () -> Unit` / `FunctionN` | Custom publish types; cover work before handoff at call site |
| Source: `ws.disconnect` | Single-line `coverTraffic.stop { ws.disconnect() }` only | Multiline correct form; app-level disconnect; helper indirection |
| Source: publish then cover | Immediate predecessor is `publish*` | Cover prep above publish; third call site added elsewhere under another name |

They are useful regression detectors for the two round-2 P1 *shapes*. They are **not** a substitute for structural emptiness of C, and calling them structural repeats the round-2 error class.

---

## What is actually solid in this round

- Real frame is published at the call site before `CoverTraffic.cover` in all three choke points.
- `paired(cover, publish)` is gone from production.
- Teardown drain before invalidate is real ownership; mid-gap lock no longer systematically marks every pairing.
- Exactly-once under the drain wait is correct and tested.
- D2c non-suspending tails preserved without putting cover work in front of the socket.
- Coordinator disconnect sites match the claim.
- 28 pairing tests + dying socket + real `stop` entry are a material improvement over round 2.

---

## VERDICT: FINDINGS (0 P1, 2 P2, 3 P3)

| ID | Sev | One-line |
|---|---|---|
| F1 | P2 | Provisioning job can be launched after `stop()`’s cancel; outlives session / can spend registration |
| F2 | P2 | “C empty by construction” overstated; tripwires miss non-Function publish and cover prep above publish |
| F3 | P3 | Admit gate / step 1 armed only after drain; comments claim otherwise |
| F4 | P3 | Step-1 impossibility argument overreaches; new-send refusal is jointly satisfiable |
| F5 | P3 | Disconnect source tripwire is format-brittle and narrow |

**Declared residuals (not counted as new open defects):** `applyTransportLocked` undrained disconnect; post-`sendMessage` pre-admit race under real-first; cross-send `sendLimit` preemption; OkHttp write coalescing.
