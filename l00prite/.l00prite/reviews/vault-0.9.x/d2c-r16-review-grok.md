# Independent adversarial security review — PR #46 round 16

| Field | Value |
| --- | --- |
| Repo | `jackofall1232/zitrone` |
| Branch | `feat/0.9.1-vault-d2c-slot-a-live` |
| Head | `9f7d187` |
| Worktree | `/root/zitrone/.claude/worktrees/agent-a6bac19a3ea40ae4f` |
| Prior head (round 15) | `1adcd00` |
| Diff | 4 files (`MessagingCoordinator.kt`, `ZitroneApp.kt`, `VaultImageStore.kt`, test) |
| Context | `/root/l00prite/d2c-r15-adjudication.md` |
| Reviewer | Independent (Grok) — report only |
| Threat model | Physical device + forensic capability; crash/kill at any point |

**Method:** Claims verified against source at `9f7d187`. The author’s six-state table was **not** trusted; states re-derived from writers/readers and concurrent transitions. Auth-clear writers re-enumerated independently.

---

## Executive summary

| Claim | Verdict |
| --- | --- |
| R15-P2: guard lifetime must match durable intent, not coroutine RAM | **FIXED** for the adjudicated failure modes (post-coroutine AMBIGUOUS/DEFINITE_FAILURE revoke; restart-with-intent-on-disk) |
| Guard = `intentMarkerPresent()` (file present, **not** `intent && !confirmed`) | **Correct choice** vs `deleteIntentPending()` for onConfirmedNotDurable crash collapse |
| `deleteInFlight` kept as pre-intent supplement | **Correct** |
| Auth-clear writer enumeration | **Accurate** for live production paths |
| Author residual (check/clear micro-window) | **Real**, fail-safe; window is **slightly wider** than “before delete initiated” (see R16-R1) |

**No P1.** No merge-blocking reopening of the adjudicated R15-P2 lifetime bug.

**One residual race (R16-R1, P3 / fail-safe):** non-atomic check-then-clear in `onSessionRevoked` can still clear tokens after a concurrent delete has made intent durable, because several statements run between the guard read and `clearTokens()`. Consequence remains fail-safe retention + stuck reconcile (same class as prior P2s, much narrower).

---

## 1. Independent writer/reader tables (re-derived)

### Intent file (`vault.delete-intent`)

| Writer | When | Meaning |
| --- | --- | --- |
| `markDeleteIntent` / `writeDurableMarker` | Start of delete after `deleteInFlight=true` | Delete initiated; server outcome unknown |
| `clearBothMarkersDurably` via `destroy()` | End of successful confirmed destroy | Intent retired with vault |
| `clearBothMarkersDurably` via `create()` | Re-onboard with no vault / stale markers | Intent cleared as hygiene (no vault-backed auth) |
| `clearDeleteIntent` | Dead production path (unused) | — |

Intent is **not** cleared on AMBIGUOUS / DEFINITE_FAILURE / confirmed-not-durable (verified `MessagingCoordinator.kt:1420–1445`).

### Auth-protection guard (union)

| Component | Source | Lifetime |
| --- | --- | --- |
| `deleteInFlight` | RAM `@Volatile` | Coroutine start → `finally` on every exit |
| `intentMarkerPresent()` → `hasDeleteIntentMarker()` | **Disk** under `imageLock` each call | From successful durable intent write until `destroy()` (or create) retires the file |

Guard expression (`MessagingCoordinator.kt:1840`):

```kotlin
if (deleteInFlight || intentMarkerPresent()) return
```

### Token / account clear writers (independent search)

| Writer | Clears | Guarded by intent∪deleteInFlight? | Notes |
| --- | --- | --- | --- |
| `onSessionRevoked` → `api.clearTokens()` (`:1850`) | tokens only | **Yes** (`:1840`) | Only live concurrent clearer |
| `ApiClient.deleteSession` → `clearTokens` | tokens | No | **No production callers** |
| `signalStore.wipe` / `VaultState.auth = AuthState()` | tokens + accountId | N/A (same delete completion) | Only after durable confirmed (`MainActivity` finishUi) |
| `VaultRuntime.close` → wipe | full state | N/A | Lock/teardown; after confirmed on delete path |
| `deleteAccount()` API | nothing | — | Classify only |
| `stop()` / `onAuthExpired()` | nothing | — | No clear |
| `clearAccount()` | accountId | no callers | Dead |

Author enumeration matches the live graph.

### Reader assumptions

| Reader | Assumption | Holds? |
| --- | --- | --- |
| `onSessionRevoked` guard | Intent file present ⇒ do not clear tokens | **Yes** for post-exit and restart once file is durably present; **gap** in non-atomic check→clear (R16-R1); **FS gap** if `exists()` false-negatives (R16-R2) |
| Splash / reconcile | `deleteIntentPending` = intent∧¬confirmed ⇒ unlock + retry DELETE | Unchanged; separate from guard |
| Auto-destroy | Only `serverDeleteConfirmed` | Unchanged |

---

## 2. Independent state/transition matrix (completeness check)

Author states re-checked from source; extra transitions added.

| # | State / transition | `deleteInFlight` | Intent file | Guard blocks clear? | Auth needed later? | OK? |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Coroutine start, before intent write | true | absent | **Yes** (flag) | No | OK |
| 2 | Intent durable, during DELETE | true | present | Yes | Yes | OK |
| 3 | `CONFIRMED_GONE` + confirm durable + destroy in progress | true → false after `onConfirmed` | present until retire | Yes while intent file remains | No (confirmed path) | OK |
| 4 | Destroy success | false | absent | No | No | OK |
| 5 | Destroy fail after durable confirmed | false after finally | intent and/or confirmed may remain | Yes if intent remains | No (DeleteIncomplete) | OK |
| 6 | `DEFINITE_FAILURE` / `AMBIGUOUS` exit | **false** (finally) | **present** | **Yes (file)** | Yes | **OK — R15-P2 closed** |
| 7 | Confirmed-not-durable exit | false | present (± unconfirmed confirmed file) | Yes (file) | Yes | OK — intent-only collapse covered |
| 8 | Intent-not-durable abort | false | may leave undurable file | Yes if file exists | No | OK |
| 9 | Process restart + intent on disk | false | present | **Yes (file)** | Yes | **OK — R15-P2 restart closed** |
| 10 | Restart + only durable confirmed | false | may be absent after partial retire | No / DeleteIncomplete | Destroy path | OK |
| 11 | Concurrent `create()` with intent, **no** vault | — | cleared by create | N/A | No vault auth | OK (hygiene) |
| 12 | Concurrent `create()` with vault present | blocked by `require(!bin.exists())` | — | — | — | OK |
| 13 | Rapid multiple revokes | — | — | Same guard each time | — | OK |
| 14 | Revoke check false, then concurrent intent write, then clear | false→true file | becomes present | **No** (already past check) | Yes | **R16-R1 residual** |
| 15 | Marker corruption / unstatable intent | — | present but `exists()` false | **May not block** | Yes | **R16-R2 residual** |

**Author table completeness:** Covers sequential delete outcomes well. Incomplete on: (a) concurrent check/clear vs delete (row 14), (b) FS indeterminate presence for the guard (row 15), (c) create-while-intent (harmless when no vault). Not incomplete on “confirmed without intent” for the guard: using intent-file-only (not `!confirmed`) is correct for onConfirmedNotDurable.

---

## 3. Does `intentMarkerPresent()` read durable disk state (no stale cache)?

**Verified chain:**

```
onSessionRevoked
  → intentMarkerPresent()          // injected
  → imageStore::hasDeleteIntentMarker   // ZitroneApp buildVaultSession :467
  → imageLock.withLock { deleteIntentFile.exists() }  // VaultImageStore.kt:804–805
```

- No process-local mirror of “intent pending” for the guard.
- Each revoke re-stats under `imageLock` (same lock as marker write/delete).
- After `writeDurableMarker` returns successfully, a later acquire of `imageLock` observes the file created under that lock (no unlocked cache).

**Not a cached boolean.** The only “staleness” is classical TOCTOU after the function returns (R16-R1), not a stale in-memory snapshot of the marker.

---

## 4. Micro-window residual(s)

### Author residual — real, fail-safe

“Revoke reads guard false in the microseconds before a delete is initiated.”

**Real:** before `deleteInFlight=true` and before intent exists, revoke clears tokens.  
**Fail-safe:** `clearTokens` does not clear `accountId`; live account can re-session via identity-signed `createSession`; no auto-destroy; no re-registration under wiped accountId. Retention / temporary logout at worst.

### R16-R1 — broader non-atomic check→clear (author understated)

**Where:** `MessagingCoordinator.onSessionRevoked` `:1840–1850`

```kotlin
if (deleteInFlight || intentMarkerPresent()) return  // releases imageLock after read
_linking.value = false
acceptingDeliveries = false
linkJob?.cancel()
api.clearTokens()  // may run AFTER concurrent intent became durable
```

**Mechanism:** Between the guard evaluation and `clearTokens()`, the confined delete coroutine can: set `deleteInFlight`, write durable intent, even finish an AMBIGUOUS DELETE. The socket thread does non-trivial work between check and clear, so this is not only a single-instruction race.

**Consequence:** Same as R15-P2 outcome — tokens cleared while intent persists → later reconcile token-less 401 → stuck retention. **Fail-safe** (no loss/leak/unauthorized destroy/silent re-reg). **Much rarer** than the fixed post-coroutine window (needs interleaving mid-`onSessionRevoked`).

**Severity: P3** (or residual of the prior P2 class with vastly reduced reachability). Not a reopening of the common AMBIGUOUS-then-revoke path (that path now has intent on disk **before** the coroutine ends, so a **new** revoke after exit sees the file).

### Other micro-windows checked

| Window | Result |
| --- | --- |
| Restart before reconcile, after intent on disk | Guard true via file — **closed** |
| Pre-intent with `deleteInFlight` true | Guard true via flag — **closed** |
| Confirmed durable → wipe | Allowed (after confirmed) — **OK** |
| create clears intent with no vault | No vault-backed tokens — **OK** |

---

## 5. Disk read on every revoke — new failure modes?

| Concern | Analysis |
| --- | --- |
| Throws from `hasDeleteIntentMarker` | `File.exists()` does not throw; `imageLock` acquire can block but not fail open by exception. **No throw-fail-open.** |
| Blocking socket thread on disk/stat | Rare path (session revoke only). Latency only; no security inversion. |
| Deadlock imageLock vs runtime lock | Guard only takes `imageLock`; `clearTokens` takes runtime lock **after** releasing imageLock. No nested lock order with mutate. **No deadlock introduced.** |
| `File.exists()` false-negative (R16-R2) | If intent is present but stat misreports absent, guard fails open → clear allowed. Fail-safe retention class; needs unhealthy FS. **P3 residual.** Prefer `!Files.notExists` for “protect unless proven absent” (not required for R15-P2 close). |

---

## 6. R15-P2 primary scenarios (source re-check)

### A. AMBIGUOUS + later revoke (adjudication scenario)

1. Intent durable; DELETE AMBIGUOUS; `onNotConfirmed`; `finally` → `deleteInFlight=false`; **intent remains**.  
2. WS still up (disconnect only on CONFIRMED step 4).  
3. Revoke → `intentMarkerPresent()==true` → **return, no clearTokens**.  

**Closed.**

### B. Restart with intent on disk

1. Process death after intent, before confirm.  
2. New process: `deleteInFlight` defaults false.  
3. Unlock → session; any revoke before/during reconcile: disk intent present → protected.  
4. Reconcile DELETE with retained tokens → 404 path available.

**Closed.**

### C. Confirmed-not-durable + crash → intent-only

Intent-file guard (not `!confirmed`) still true through non-durable confirmed write; crash drops unconfirmed confirmed; intent remains; auth kept.

**Closed** (reason intent-only, not `deleteIntentPending`, is correct).

---

## Findings

### None at P1 / P2 (merge-blocking)

The adjudicated R15-P2 lifetime mismatch is fixed: post-coroutine and cross-restart protection derives from the durable intent file, with `deleteInFlight` covering pre-intent coroutine start.

### R16-R1 — P3 — non-atomic guard check vs `clearTokens` (residual)

- **File/function:** `MessagingCoordinator.onSessionRevoked` (`:1840–1850`)  
- **Mechanism:** Guard is check-then-act; concurrent delete can make intent durable after the check and before `clearTokens`.  
- **Effect:** Fail-safe retention / stuck reconcile if tokens are wiped while intent persists.  
- **Not** the common sequential AMBIGUOUS→revoke path (that is fixed).

### R16-R2 — P3 residual — guard presence uses `File.exists()` not fail-closed tristate

- **File/function:** `VaultImageStore.hasDeleteIntentMarker` (`:804–805`)  
- **Mechanism:** `exists()==false` can mean absent **or** indeterminate; protection needs “block clear unless proven absent.”  
- **Effect:** On rare FS stat failure with intent present, revoke may clear tokens (fail-safe retention).  
- Orthogonal to create’s absence-proof tristate (already fail-closed for R14-2).

### Informational

- `deleteSession` still unguarded and unused.  
- Natural token expiry residual unchanged.  
- Author’s residual is real; R16-R1 is the same atomicity class with a slightly larger interleaving window than described.

---

## Explicit CLEAN

| Check | Result |
| --- | --- |
| R15-P2 post-coroutine AMBIGUOUS/DEFINITE_FAILURE | **CLEAN** |
| R15-P2 restart-with-intent | **CLEAN** |
| Intent-file vs `intent&&!confirmed` choice | **CLEAN** (correct for onConfirmedNotDurable) |
| Injection production wiring | **CLEAN** (`imageStore::hasDeleteIntentMarker`) |
| No cached RAM mirror of intent for guard | **CLEAN** (fresh disk stat under lock) |
| Auth writer enumeration | **CLEAN** (matches live graph) |
| create concurrent with pending delete | **CLEAN** (no vault / blocked if vault exists) |
| New throw/deadlock from disk read on revoke | **None found** |
| Unauthorized destroy / silent re-registration | **Not introduced** |

---

## Bottom line

Round 16 correctly moves the auth-survival guard onto the **durable intent marker’s lifetime**, which is the right acceptance criterion from the R15 adjudication. The primary, realistic R15-P2 scenarios are closed in source.

Remaining issues are **narrow fail-safe residuals** (non-atomic check/clear; `exists()` false-negative under FS fault), not a reopening of the durable-lifetime bug and not P1 data-integrity / exploit paths.

**No P1/P2 merge-blocking findings in this pass.**
