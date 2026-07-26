# Independent adversarial security review — PR #46 round 15

| Field | Value |
| --- | --- |
| Repo | `jackofall1232/zitrone` |
| Branch | `feat/0.9.1-vault-d2c-slot-a-live` |
| Head | `1adcd00` |
| Worktree | `/root/zitrone/.claude/worktrees/agent-a6bac19a3ea40ae4f` |
| Prior head (round 14) | `5a136e9` |
| Diff | `git diff 5a136e9...1adcd00` (2 files: `MessagingCoordinator.kt`, `VaultImageStore.kt`) |
| Context | `/root/l00prite/d2c-r14-adjudication.md` |
| Reviewer | Independent (Grok) — report only |
| Threat model | Physical device + forensic capability; crash/kill at any point |

**Method:** Claims verified against source at `1adcd00`. Every production auth-clear writer enumerated; every marker-absence check in the delete-marker lifecycle inspected. No deference to the round-15 self-report.

---

## Executive summary

| Round-14 finding | Round-15 claim | Verified |
| --- | --- | --- |
| **R14-1 (P2)** revoke-race token clear in intent→confirmed window | `@Volatile deleteInFlight` set for whole delete coroutine; `onSessionRevoked` early-return | **FIXED** for the load-bearing race |
| **R14-2 (P2)** `File.exists()` conflates absent / indeterminate on marker re-stat | `Files.notExists` tristate in `clearBothMarkersDurably`, `clearDeleteIntent`, and create’s pre-clear gate | **FIXED** (fail-closed) |

**No P1/P2 findings.** No merge-blocking defect identified in this pass. Documented residuals are intentional fail-safe routing bias and dead-code surfaces, not regressions of the two fixed classes.

The severe prior classes remain closed (intent-only never auto-destroys; confirmed-only auto-destroy; no early `clearAccount` / silent re-registration; create cannot succeed beside a non-proven-absent confirmed marker).

---

## 1. Auth-token / accountId clear paths (enumeration)

Production writers that can clear vault-backed auth material:

| # | Writer | What it clears | Honors `deleteInFlight`? | Can race intent→confirmed? |
| --- | --- | --- | --- | --- |
| A | `MessagingCoordinator.onSessionRevoked` → `api.clearTokens()` (`MessagingCoordinator.kt:1819–1836`) | access + refresh only | **Yes** — `if (deleteInFlight) return` before clear | **No** while flag is true (the R14-1 window) |
| B | `ApiClient.deleteSession()` → `clearTokens()` in `finally` (`ApiClient.kt:174–178`) | access + refresh | **No** | **N/A** — **no production call sites** (API only; grep shows definition only) |
| C | `MainActivity` delete `finishUi` → `signalStore.wipe()` → `VaultState.wipe()` → `auth = AuthState()` (`MainActivity.kt:973`, `VaultState.kt:83–90`) | tokens **and** accountId | N/A (same flow) | **No** — only runs in `onConfirmed` **after** durable `vault.delete-confirmed` |
| D | `VaultRuntime.close()` → `state.wipe()` | full auth | N/A | Only via lock/teardown; delete’s wipe/destroy path is after confirmed; normal lock does not race a live delete’s confirm write for the R14-1 window |
| E | `AuthStore.clearAccount()` | accountId | never called from production (no call sites) | N/A |
| F | `ApiClient.deleteAccount()` | — | — | **Does not clear auth** (round 14) |

**Non-clearers (verified):**

- `MessagingCoordinator.stop()` — disconnect / cancel only; **no** `clearTokens` (`:539+`).
- `onAuthExpired()` — re-`start()` only; **no** token clear (`:1853–1866`).
- `storeTokens` — writes tokens; does not clear.

**Verdict on claim “every path that can clear auth tokens now honors the guard”:**

- Among **reachable concurrent** writers that could hit the intent→confirmed window, **only A (`onSessionRevoked`)** exists; it is guarded.
- **B (`deleteSession`)** is an unguarded public sink but is **dead code** in this tree (no callers). Not a live race.
- **C** clears auth **after** confirmed is durable — allowed by the global invariant (“auth survives **until** confirmed is durable”), not a violation.

The self-report’s enumeration matches the live call graph. Guarding only `onSessionRevoked` is sufficient for production concurrency; it is **not** a “third writer left unguarded” situation for live code.

---

## 2. `deleteInFlight` lifecycle (crash / restart / stuck-true)

| Event | Flag behavior | Auth / markers |
| --- | --- | --- |
| Enter `deleteAccountAndWipe` coroutine | `deleteInFlight = true` first (`:1381`) | — |
| Any exit (success, not-confirmed, confirmed-not-durable, intent fail, throw) | `finally { deleteInFlight = false }` (`:1447–1449`) | Flag cannot latch true across normal returns |
| Process death mid-delete | RAM flag **gone** (defaults false on new process) | Durable truth is **disk**: intent ± confirmed + vault auth blobs — not the flag |
| Restart with intent only | Flag false | Splash → Locked; post-unlock reconcile; tokens still on vault if never cleared |
| Restart with confirmed | Flag false | DeleteIncomplete; tokens not required |

**Re-derive from durable state on restart?** The flag is **not** re-derived — and **must not** be. After restart, recovery is:

- `serverDeleteConfirmed()` → auto-destroy path, or  
- `deleteIntentPending()` → unlock + authenticated reconcile.

That is correct: a process-local mutex/guard should not be persisted. Reopening the R14-1 race would require a **live** `onSessionRevoked` during a **live** delete coroutine; after restart there is no in-flight delete until reconcile starts a new one (which sets the flag again before DELETE).

**Stuck-true hang?** `finally` always clears on coroutine completion. A stuck-true requires the NonCancellable coroutine never completing (hung network with no timeout). That blocks further revoke clears (conservative) and is the pre-existing long-delete UX under `beginTerminalWipe`, not a new permanent “auth never clearable after process recovery” hang. Process death clears the flag.

**Ordering note:** Flag is set at the **start** of the confined coroutine (before intent write), held through `onConfirmed()` (wipe + destroy), then cleared. The R14-1 window (post-`CONFIRMED_GONE`, pre-durable confirmed) only exists **while** the coroutine is running ⇒ flag is true. Queue delay before the coroutine starts is **before** this attempt’s DELETE; it does not recreate the server-gone-token-stripped crash window for this attempt.

**Verdict:** Lifecycle is crash-safe for the intended recovery model. No stuck-true post-restart defect.

---

## 3. Marker-absence tristate (`Files.notExists`)

### Sites fixed (verified)

| Site | Check | Fail-closed? |
| --- | --- | --- |
| `create` pre-write gate (`VaultImageStore.kt:409–413`) | Clear unless **both** `Files.notExists` | Indeterminate ⇒ `notExists` false ⇒ force clear path; clear failure ⇒ throw, **no vault written** |
| `clearBothMarkersDurably` (`:688–699`) | `dirSync` DURABLE **and** both `Files.notExists` | Indeterminate ⇒ false ⇒ callers throw / abort create |
| `clearDeleteIntent` (`:670–677`) | no-op only if `Files.notExists`; post-delete requires `notExists` + DURABLE dirSync | Indeterminate after delete ⇒ throw `DestroyFailed` |

JDK semantics: `Files.notExists` is **true only** when the path is confirmed absent; present **or** indeterminate ⇒ **false**. Used as “proven absent,” so indeterminate never treated as absent.

No fall-through `when`/`else` that treats indeterminate as absent — boolean `&&` / early-return structure only.

### Related `File.exists()` **not** converted (and why)

| Site | Role | Bias if exists misreports false on I/O error |
| --- | --- | --- |
| `serverDeleteConfirmed()` (`:780`) | Auto-destroy **authorization** | Treats as **not** confirmed → **no** auto-destroy (retention) — safe direction for destroy authority |
| `deleteIntentPending()` (`:790`) | Reconcile trigger | May under- or over-trigger reconcile under FS fault; **never** grants auto-destroy alone |
| `writeDurableMarker` post-`createNewFile` (`:706`) | Prove marker **present** for durable create | `exists` false ⇒ write fails closed |
| Vault file unlink verify in `destroy` | Prove **presence** of survivors | exists false on surviving file could under-detect survivors — pre-existing destroy verify shape, **out of R14-2 scope** (markers, not vault files) |

Round-15 commit message states routing readers **deliberately** keep `File.exists()` so indeterminate false biases away from auto-destroy. That matches fail-safe for the confirmed-marker **reader**. The R14-2 defect class was **absence proof** for create/clear (where false “absent” is catastrophic). Those sites are fixed.

**Verdict:** Every load-bearing **marker-absence** check in create/clear/retire is tristate fail-closed. Remaining `File.exists()` on marker **presence for routing** is intentional safe bias, not a half-applied fix of the create path.

---

## 4. WRITER / READER tables

### `deleteInFlight` (process-local)

| Writer | Meaning | Durable? |
| --- | --- | --- |
| `deleteAccountAndWipe` set true (`:1381`) | “Delete coroutine owns teardown; external token clear forbidden” | No (RAM) |
| `finally` set false (`:1448`) | “Delete coroutine finished; normal revoke may clear again” | No |

| Reader | Assumption | Holds for crash / restart? |
| --- | --- | --- |
| `onSessionRevoked` early return (`:1826`) | Flag true ⇒ do not clear tokens / force logout | **Yes while coroutine runs** (covers R14-1 window). After death, flag false; recovery uses **disk** markers + vault auth, not the flag |
| (no other readers) | — | — |

### Marker absence (create / clear)

| Writer | Meaning | Guaranteed? |
| --- | --- | --- |
| `clearBothMarkersDurably` | Both markers proven absent + retire dirSync durable | Yes if returns true; else callers fail closed |
| `clearDeleteIntent` | Intent proven absent + dirSync durable | Yes or throw |
| `create` after successful clear gate | May write successor vault | Only if markers confirmed absent or clear returned true |

| Reader | Assumption | Holds? |
| --- | --- | --- |
| Splash / DeleteIncomplete (`serverDeleteConfirmed`) | confirmed present ⇒ destroy owed | Fail-safe if stat under-reports presence (no auto-destroy) |
| Reconcile (`deleteIntentPending`) | intent-only ⇒ retry DELETE | Not auto-destroy |
| `create` | may write only if no stale confirmed | **Yes** with tristate |

### Auth survival vs confirmed marker (global invariant)

| Writer | Ordered vs durable confirmed? |
| --- | --- |
| `deleteAccount` (API) | Does not clear |
| `onSessionRevoked` during `deleteInFlight` | Does not clear (guarded) |
| `onSessionRevoked` outside delete | May clear (normal) |
| `wipe` / `destroy` after durable confirmed | Clears/destroys auth **after** confirmed — allowed |

---

## 5. New defects from these fixes?

| Concern | Result |
| --- | --- |
| Guard only on one site, other writers free | Live concurrent writers: only `onSessionRevoked`. **No new third-writer hole** |
| `finally` clears flag too early (before destroy) | Flag held through synchronous `onConfirmed()` (includes wipe+destroy). **No** |
| Stuck `deleteInFlight` after crash | RAM-only; restart false. **No** |
| Suppressing revoke leaves zombie session on AMBIGUOUS | Intentional; 401 / next reconcile. **Not a security regression** |
| Tristate false-positive blocks create forever | On broken FS fail-closed; healthy storage unaffected. **No** |
| Create skips clear when both `notExists` | Correct; clear still fail-closed if clear path taken. **No** |
| Indent / style in `deleteAccountAndWipe` | Cosmetic only |

**No new P1/P2 defect found** of the “small fix introduced a regression” class.

---

## Findings

### None at P1 / P2

Both adjudicated round-14 defects are closed in source for their stated failure modes.

### Residuals (informational / not merge-blocking)

**R15-I1 — `ApiClient.deleteSession()` / public `clearTokens()` unguarded**  
Dead for production call graph; if wired later without a guard, would re-open an external clear path. Defense-in-depth only — **not** a live defect at `1adcd00`.

**R15-I2 — Marker **routing** still uses `File.exists()`**  
Deliberate fail-safe bias for auto-destroy authorization (indeterminate → not confirmed). Distinct from R14-2 (absence proof for create). Under rare FS faults, may under-trigger DeleteIncomplete and rely on reconcile — retention, not successor auto-destroy.

**R15-I3 — Natural token expiry residual**  
Unrelated to revoke race; still possible if tokens expire before reconcile without a concurrent clear. Pre-existing product residual; not reintroduced by round 15.

---

## Explicit CLEAN checklist

| Check | Result |
| --- | --- |
| R14-1: revoke cannot clear tokens in intent→confirmed while delete coroutine runs | **CLEAN** |
| Auth-clear enumeration: all live concurrent writers | **CLEAN** (only guarded revoke) |
| `deleteInFlight` crash/restart | **CLEAN** (disk markers + vault auth are the durable recovery signals) |
| `deleteInFlight` stuck-true after process recovery | **CLEAN** |
| R14-2: create / clear absence proof | **CLEAN** (`Files.notExists` fail-closed) |
| Indeterminate treated as absent | **CLEAN** (never) |
| Successor vault + stale confirmed after successful `create` | **CLEAN** |
| New regression from guard or tristate | **None found** |

---

## Bottom line

Round 15 correctly closes both confirmed round-14 P2s at the code paths that matter:

1. **Global auth survival through the delete window** is enforced against the only live concurrent clearer (`onSessionRevoked`) via `@Volatile deleteInFlight` + `finally`.  
2. **Marker absence** for create/clear/retire is proven with `Files.notExists` tristate semantics, fail-closed.

Independent pass finds **no P1/P2 findings** and no demonstrated new invariant break from these mechanical changes. Residual notes are dead-code hygiene and intentional routing fail-safe bias, not reopened auto-destroy or silent re-registration classes.
