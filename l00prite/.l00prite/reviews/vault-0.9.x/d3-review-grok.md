# D3 Adversarial Security Review: Does Auto-Lock Write Delete/Token State?

**Scope:** Report-only. Sole question: does idle auto-lock introduce a **new writer** to `vault.delete-intent`, `vault.delete-confirmed`, or token-clearing (`ApiClient.clearTokens` / `AuthStore.clearTokens` / `clearAccount`)?

---

## Verdict

### **NO — auto-lock does not write delete/token state under any traced timing.**

Auto-lock only ever reaches durable state through the **existing lock path** (`UnlockController.lock` → reseal + RAM teardown). That path does not create/update delete markers and does not clear auth tokens. TOCTOU with a concurrent delete may **race teardown**, but auto-lock’s contribution remains `lock()` only — it does not become a marker/token writer.

---

## 1. Teardown path: what auto-lock actually does

### Call graph (from D3 + collaborators)

```
VaultLockManager (onStop / timer)
  └─ lock()                          // injected: unlockController.lock()
       └─ synchronized { lockCurrent() }
            ├─ stopSession(session)  // coordinator.stop() + runtime.close()
            ├─ sessionScope?.cancel()
            ├─ runBlocking { job.join() }  // bounded drain
            ├─ publish(null)
            ├─ current = null
            └─ sessionScope = null
```

**VaultLockManager** (`VaultLockManager.kt`):

- Decision helpers: pure; no I/O.
- Side effects: `scope.launch { lock() }` or delayed `lock()` only.
- No references to marker files, `AuthStore`, `ApiClient`, or any wipe-beyond-lock.

**`lockCurrent()`** (unchanged collaborator):

- `stopSession` → catch → cancel scope → drain → `publish(null)` → clear handles.
- No delete markers; no token clear.

**`MessagingCoordinator.stop()`**:

- Flags, cancel `linkJob`, `ws.disconnect()`, `notificationScheduler.cancelAll()`, `pendingPostAck.clear()`.
- Network/notification teardown only.

**`VaultRuntime.close()`**:

```text
session.close()  // finally always: state.wipe(); closed = true
```

- `state.wipe()` is in-memory after close; not account-delete durable signaling.

**`VaultSession.close()`**:

```text
doFlush()                    // final reseal of current state
finally: wipe(vaultKey); wipe(payload)  // RAM
```

- No marker paths; no auth nulling in the shown contract.

### Conclusion for (1)

The only durable write on this path is **vault reseal via `doFlush()`** (current sealed state). That is lock semantics, not delete/token semantics.

---

### (a) Rapid background/foreground cycling

```kotlin
override fun onStop(...) {
    pending?.cancel()
    pending = when (val action = autoLockOnBackground(...)) {
        None -> null
        LockNow -> scope.launch { lock() }
        is LockAfter -> scope.launch {
            delay(...); if (shouldAutoLockAtFireTime(...)) lock()
        }
    }
}
override fun onStart(...) {
    pending?.cancel()
    pending = null
}
```

| Concern | Assessment |
|--------|------------|
| Duplicate timers | `onStop` always `pending?.cancel()` first → at most one scheduled Job reference. |
| Missed cancel on `delay` | `Job.cancel()` aborts `delay`; fire body skipped. |
| `LockNow` already in `lock()` | `lock()` is non-suspend / not cooperative; cancel may not stop an in-flight lock. Result: vault locks. Still not markers/tokens. |
| Overlapping `lock()` | `lock()` is `synchronized` + idempotent when `current == null`. |

**Refute:** Cycling cannot turn auto-lock into a delete/token writer. Worst case is redundant/idempotent lock.

---

### (b) TOCTOU: `shouldAutoLockAtFireTime` then `lock()`

```kotlin
if (shouldAutoLockAtFireTime(sessionLive(), terminalWipe())) lock()
```

Not atomic with `beginTerminalWipe()` / `deleteAccountAndWipe`.

| What can happen | Does auto-lock write delete/token state? |
|-----------------|------------------------------------------|
| Check true → delete starts → `lock()` races delete teardown | **No.** Auto-lock still only calls `lock()`. Markers/tokens (if any) come from the **delete** path, not from auto-lock. |
| Check true → session already null → `lockCurrent` early-returns | **No** durable writes beyond whatever prior teardown already did. |
| Check false (`terminalWipe`) → skip | **No** lock; delete owns teardown as intended. |

**Defense-in-depth residual (not a new writer):** concurrent `lock()` during delete can still interleave with delete’s ordered teardown. Comments say delete’s NonCancellable + closed-runtime fail-safe tolerate it; auto-lock’s skip is hygiene, not the sole safety bar.

**Refute** the “new writer” claim even under TOCTOU.  
**Flag** residual teardown-race (severity below).

---

### (c) Process kill in scheduled-but-not-fired window

- Timer is an in-memory coroutine `Job`.
- Kill discards process state → no `lock()`, no auto-lock I/O.
- No durable “pending auto-lock” file.
- Restart: session not live until unlock; no orphan markers from auto-lock.

**Refute.**

---

## 2. `VaultSession.close()` reseal vs wipe / markers

| Claim | Evidence | Result |
|-------|----------|--------|
| `doFlush` reseals **current** state | “Best-effort final reseal of the state as of teardown… this flush captures everything” | **Verified** (contract) |
| Does not null auth | Flush is capture-of-current; no null-auth step in `close()` | **Verified** (shown path) |
| Does not touch marker files | No marker API in `close` / `runtime.close` | **Verified** |
| Runtime wipe is RAM-only | `finally { state.wipe(); closed = true }` after `session.close()`; session `finally` wipes `vaultKey`/`payload` | **Verified** |

**Implication for the ONLY question:** Auto-lock’s durable effect is **auth-preserving reseal + session drop**, not auth wipe and not delete confirmation.

*(Note: `doFlush` body is not in the pasted text; assessment uses the close-path contract and comments. If `doFlush` ever nulls auth, that would be a pre-existing lock-path issue, not a D3-only writer — still not delete markers.)*

---

## 3. `AutoLockDecisionTest` branch matrix

**Present (5 tests):**

| Branch | Covered? |
|--------|----------|
| `!sessionLive` → `None` (incl. timeout 0) | Yes |
| `terminalWipe` → `None` (0 and 300) | Yes |
| `timeoutSeconds == 0` → `LockNow` | Yes |
| `60 / 300 / 900` → `LockAfter(ms)` | Yes |
| Fire: live && !wipe | Yes |
| Fire: live && wipe | Yes |
| Fire: !live && !wipe | Yes |

**Uncovered / thin:**

| Gap | Why it matters |
|-----|----------------|
| `timeoutSeconds < 0` → `LockNow` (`<= 0`) | Code branch; test title says “zero or negative” but only `0` is asserted. |
| Fire: `sessionLive=false && terminalWipe=true` | Should be `false`; untested corner of the 2×2. |
| Schedule: `!sessionLive && terminalWipe` | Redundant with first `when` arm; not asserted. |
| **No host test of `VaultLockManager` glue** | Cancel-on-`onStart`, fire-time recheck under concurrent terminal wipe, double-`onStop`, Job cancel vs in-flight `lock()` — lifecycle surface is explicitly untested. |
| TOCTOU check→`lock()` | Pure helpers covered; atomicity with delete is not. |

Pure decision matrix for product timeouts **0/60/300/900 + both None + fire gating** is essentially covered; negatives and lifecycle/TOCTOU are not.

---

## New defects (non–delete/token, still in scope)

### Low — TOCTOU residual vs delete teardown
Fire-time gate then `lock()` is not under the same lock as `beginTerminalWipe()`. Can still call `lock()` after wipe begins. **Does not write markers/tokens**; can still race delete’s ordered stop. Pre-existing lock is shared; severity is “extra concurrent lock during wipe,” not new durable semantics.

### Low — `register()` not idempotent / no unregister
```kotlin
fun register(lifecycle: Lifecycle) {
    lifecycle.addObserver(this)
}
```
Double `register` → two observers → duplicate timers/`lock()` calls. Construction uses a single `.also { it.register(ProcessLifecycleOwner...) }`. Latent if wiring is copied later. Process-lifetime observer with no `removeObserver` is acceptable for `AppContainer` lifetime.

### Low / informational — `pending` not synchronized
Lifecycle callbacks are main-thread; assignment races are unlikely. Fire runs on `scope`; cancel is Job-safe. No delete/token impact.

### None found — main-thread block of lock drain
`onStop`/`onStart` only `cancel` + `launch`; `lock()` (and its `runBlocking` drain) runs on process `scope`, not on the lifecycle main thread. Matches the stated design. *(If `AppContainer.scope` were ever `Dispatchers.Main`, that would reintroduce main-thread drain — pre-existing risk if misconfigured, not shown as D3-specific.)*

### None found — leaked observer under normal process lifetime
Single register on process lifecycle; lives with the process. Not a session leak.

### None found — auto-lock as second teardown implementation
Explicitly reuses `UnlockController.lock`; no parallel wipe/delete path.

---

## Explicit answers

| Question | Answer |
|----------|--------|
| **Does auto-lock write delete/token state under any traced timing?** | **NO** |
| (1a) Rapid cycle → markers/tokens? | **No** (idempotent lock only) |
| (1b) TOCTOU with delete → auto-lock writes markers/tokens? | **No** (may race lock; still only `lock()`) |
| (1c) Process kill mid-timer → durable delete/token writes? | **No** (in-memory Job only) |
| (2) `doFlush` retains auth; wipe RAM-only; no markers? | **Yes** (on shown path) |
| (3) Full branch matrix in 5 tests? | **Mostly yes** for product timeouts/None/fire; **gaps**: negative timeout, fire `(false,true)`, no lifecycle/TOCTOU integration tests |

---

## Findings by severity

### Critical
*None.* Auto-lock is not a new writer of `vault.delete-intent`, `vault.delete-confirmed`, or token-clearing APIs.

### High
*None* on the ONLY question (delete/token integrity via auto-lock as a new writer).

### Medium
*None* that establish auto-lock writing delete/token state.

### Low
1. **TOCTOU** between `shouldAutoLockAtFireTime(...)` and `lock()` vs `beginTerminalWipe()` — residual concurrent lock during delete (hygiene / ordering), not a new durable writer.
2. **`register()` double-add** if ever called twice — duplicate observers/timers.
3. **Test gaps:** negative timeout; fire-time `(sessionLive=false, terminalWipe=true)`; no `VaultLockManager` lifecycle/cancel/race tests.

### Informational
4. Auto-lock durable side effect = **auth-preserving vault reseal** (same as manual/forced lock) — intentional lock behavior, not account delete.
5. Settings/UI for `autoLockTimeoutSeconds` write **device SharedPreferences only** — orthogonal to vault delete/token state.

---

**Bottom line:** D3 auto-lock is a **new scheduler** for the **existing lock teardown**, not a new writer of the hardened delete/token surface. Under rapid cycling, TOCTOU with delete, and process kill, it still never touches `vault.delete-intent`, `vault.delete-confirmed`, or token-clearing.
