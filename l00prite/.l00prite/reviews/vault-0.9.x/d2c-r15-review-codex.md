# Independent adversarial security review — Zitrone PR #46, round 15

Reviewed only `5a136e9...1adcd00` against the source at `1adcd00`, with the round-14 adjudication as context.

## Findings

### P2 — `deleteInFlight` protects only one coroutine invocation, not the durable intent state, and its check is racy

**Location:** `apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt`, `deleteAccountAndWipe()` lines 1374-1450 and `onSessionRevoked()` lines 1818-1836

The new guard does not restore the claimed global invariant that auth survives from durable intent until durable confirmation.

First, its lifetime is shorter than the durable state it protects. `deleteAccountAndWipe()` keeps `vault.delete-intent` after both `DEFINITE_FAILURE` and `AMBIGUOUS`, and after a failed confirmed-marker write, specifically so a later unlock can reconcile. Nevertheless, the unconditional `finally` resets `deleteInFlight=false` on all of those exits. A subsequent `session.revoked` then clears tokens even though the durable state is still intent-present/confirmed-absent and the future reconcile still requires those tokens. After process death the guard is also initialized to false; it is never derived from `vault.delete-intent`. On the next unlock, the coordinator starts its socket and the Compose reconcile separately, so a revoke can clear tokens before the reconcile coroutine calls `deleteAccountAndWipe()` and sets the guard again.

Second, `@Volatile` supplies visibility but no atomicity between the check and the clear. This interleaving remains legal:

1. `onSessionRevoked()` reads `deleteInFlight == false` at line 1826.
2. The delete coroutine sets it true at line 1381 and durably writes the intent.
3. The already-past-check revoke callback executes `api.clearTokens()` at line 1836.

There is an analogous scheduling window before the launched delete coroutine first executes: `deleteAccountAndWipe()` returns after enqueueing work, so a revoke can clear tokens before line 1381 runs.

In all cases the cleared-token mutation can become durable through the ordinary coalesced flush or forced-logout session close. If the server is already gone but the confirmed marker is not durable, the intent-only reconcile sends a tokenless DELETE, receives 401/`DEFINITE_FAILURE`, retains intent, and cannot finish local destruction. This is the same fail-safe retention/stuck-reconcile consequence adjudicated P2 in round 14. The round-15 mechanical guard therefore does not close the confirmed finding.

## Exhaustive auth/account mutation inventory

| Code path | Mutation | Reachable production caller | Guard relationship |
|---|---|---|---|
| `MessagingCoordinator.onSessionRevoked()` → `ApiClient.clearTokens()` | Clears access and refresh tokens; retains account id | Yes, WebSocket callback | Checks `deleteInFlight`, but the finding above shows the guard lifetime and check/clear atomicity are insufficient. |
| `ApiClient.deleteSession()` `finally` → `clearTokens()` | Clears access and refresh tokens | No call site in production source | Does not know or check `deleteInFlight`. Dormant today, so not a separate reachable defect, but the claim that every token-clearing path honors the guard is false at code level. |
| `ApiClient.deleteAccount()` | None in round 15 | Account-delete flow | Clean: classification only. |
| `EncryptedAuthStore.clearTokens()` / `VaultAuthStore.clearTokens()` | Underlying token writers | Only through `ApiClient.clearTokens()` in production | No independent guard; relies on caller. |
| `EncryptedAuthStore.clearAccount()` / `VaultAuthStore.clearAccount()` | Clears account id | No production call site at this head | No guard, but unreachable. |
| `AuthStore.accountId = null` | Clears account id | No production assignment; registration writes only non-null values | Unreachable. |
| `VaultImageStore.destroy()` | Deletes the entire vault, including auth | Only after durable `vault.delete-confirmed` through account-delete completion/`DeleteIncomplete` | Correctly occurs after confirmation; guard is irrelevant because the whole vault is removed. |

No third reachable token/account clear beyond `onSessionRevoked()` was found. The remaining defect is that its new guard does not represent the durable phase and does not serialize the guarded action.

## `deleteInFlight` writer/reader invariant table

| Writer/state transition | Intended meaning | Actual meaning, including crash/restart |
|---|---|---|
| Field initialization | No account delete needs auth protection | Always false on construction, even when durable intent exists and confirmed does not. Meaning does not survive restart. |
| `deleteAccountAndWipe()` line 1381 sets true | Delete owns teardown; tokens must not be cleared | True only after its queued coroutine begins. It does not cover the call-to-start scheduling window. |
| Intent write succeeds while true | Durable intent exists; auth is needed until confirmation/reconcile | Correct during this particular invocation, subject to the non-atomic revoke check. |
| `finally` sets false after not-confirmed/confirmation-write failure | Invocation ended | Durable intent remains and later reconcile still needs auth, so false does not mean auth is safe to clear. |
| `finally` sets false after confirmed destroy | Delete completed | Correct; vault/auth are already gone. |
| Process death/restart | Reconstruct protection from durable state | No reconstruction occurs; new coordinator starts false regardless of intent marker. |

| Reader | Assumption | Holds for every writer state? |
|---|---|---|
| `onSessionRevoked()` line 1826 | False means clearing tokens cannot break deletion recovery | **No.** False also means persistent intent between attempts, post-crash intent, pre-coroutine scheduling, or a callback that passed its check just before true was stored. |
| Delete-flow comments/callers | True covers the whole intent→confirmed interval | **No.** It covers most of one invocation, not the durable interval across retries/restart, and does not atomically exclude clearing. |

## Marker-absence writer/reader invariant table

| Writer/check | Meaning | Mid-operation/crash behavior | Verified result |
|---|---|---|---|
| `create()` preflight: both `Files.notExists()` | Both markers are definitely absent | Present or indeterminate returns false and enters durable clear before any vault byte | **CLEAN.** No fail-open default branch. |
| `clearBothMarkersDurably()` | Both unlinks are directory-fsynced and both paths are definitely absent | `NOT_DURABLE`, present, or indeterminate returns false; create throws before writes and destroy throws | **CLEAN.** Boolean conjunction fails closed. |
| `clearDeleteIntent()` initial `Files.notExists()` | Confirmed absence permits no-op | Present or indeterminate falls through to delete/verify | **CLEAN.** Indeterminate is not treated as absent. |
| `clearDeleteIntent()` post-delete `Files.notExists()` | Intent is definitely absent | Present or indeterminate throws; failed fsync also throws | **CLEAN.** |
| `writeDurableMarker()` `File.exists()` | Marker is present before successful fsync | `exists()==false`, including indeterminate, causes failure | **CLEAN/fail-closed.** This is a presence proof, not an absence authorization. |
| `serverDeleteConfirmed()` `File.exists()` | Presence authorizes auto-destroy | Indeterminate returns false and withholds destroy | **CLEAN/fail-safe.** May delay cleanup but cannot destroy a live vault. |
| `deleteIntentPending()` uses `intent.exists() && !confirmed.exists()` | Decide whether to run conservative authenticated reconcile | Indeterminate intent can delay reconcile; indeterminate confirmed can cause a harmless reconcile/404 path | **CLEAN/fail-safe.** This remaining marker-absence expression does not authorize create or destroy. |

All load-bearing marker-absence checks used to permit successor creation or declare retirement were changed to `Files.notExists()`. The indeterminate branch is fail-closed in each. No new defect was found in the tristate change.

## Additional requested checks

- **Stuck-true behavior:** no permanent in-process latch was found; `finally` clears the flag on throws and callbacks. The defect is the opposite—false too early and after restart. A genuinely non-returning delete keeps it true, which matches an operation still in progress and does not create a separate demonstrated integrity failure.
- **Round-15 marker changes:** **CLEAN.** No relevant `File.exists()==false` authorization remains in `create()`, `clearBothMarkersDurably()`, or `clearDeleteIntent()`.
- **New regressions:** no separate new defect beyond the guard's flawed lifecycle/atomicity was verified.
