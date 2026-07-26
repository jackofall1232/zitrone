# Independent adversarial security review — Zitrone PR #46, round 16

Reviewed only `1adcd00...9f7d187` against the source at `9f7d187`, with the round-15 adjudication and commit message read as untrusted context.

## Finding

### P2 — An indeterminate intent-marker stat fails open and permits token clearing

**Locations:** `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt`, `hasDeleteIntentMarker()` lines 791-805; `apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt`, `onSessionRevoked()` lines 1828-1850

`intentMarkerPresent()` is a fresh filesystem read under `imageLock`, not a cached mirror, but its production implementation is `deleteIntentFile.exists()`. `java.io.File.exists()` returns `false` both for confirmed absence and when file status cannot be determined because of an I/O/access failure. The new security decision therefore fails open on an indeterminate read:

1. `vault.delete-intent` is present and the future reconcile still needs authentication.
2. A session revoke arrives while the filesystem cannot stat the marker reliably.
3. `deleteInFlight` is false between delete attempts or after restart, and `hasDeleteIntentMarker()` returns false because the stat is indeterminate.
4. `onSessionRevoked()` passes the guard and calls `api.clearTokens()`.
5. If the account was actually deleted during an ambiguous request, the later intent-only reconcile sends a tokenless DELETE, receives 401/`DEFINITE_FAILURE`, and cannot produce the confirmed marker or finish local destruction.

This is the same narrow fail-safe retention/stuck-reconcile consequence as the adjudicated round-15 P2: no plaintext disclosure, data loss, re-registration, or unauthorized destruction, but a requested deletion can remain incomplete indefinitely. It is also a new failure characteristic introduced by replacing a volatile read with a filesystem decision on the callback path. A thrown stat/security exception is not caught either; it aborts the revoke callback before token clearing (safe for secrecy/integrity) but can cause an availability failure on that callback thread. The actionable security defect is the ordinary indeterminate-as-false path.

## Independent writer/reader table — intent marker and auth guard

| Writer / transition | Durable or in-memory state | Meaning after the transition | Crash/restart result |
|---|---|---|---|
| `deleteAccountAndWipe()` sets `deleteInFlight=true` before marker creation | RAM only | A delete coroutine has begun; token clearing must be suppressed before the marker is visible | Lost on crash. Safe if the intent was not durable because the server request is not sent before required marker success. |
| `markDeleteIntent()` / `writeDurableMarker()` returns | Durable `vault.delete-intent` present | Delete was initiated; server outcome may be live, gone, or ambiguous. Auth is needed for reconciliation until confirmed destroy retires the marker. | File presence survives process restart and is read afresh by the new coordinator. |
| DELETE returns `DEFINITE_FAILURE` or `AMBIGUOUS` | Intent remains; coroutine later clears `deleteInFlight` | Outcome is not confirmed; auth remains protected by intent-file presence | Correct across restart unless the guard's stat is indeterminate (finding). |
| DELETE returns `CONFIRMED_GONE`, confirmed-marker write fails | Intent remains; confirmed marker may be absent or unconfirmed | Reconcile must retain auth and repeat idempotent DELETE | Intent presence protects after coroutine exit/restart, subject to finding. |
| Confirmed marker becomes durable | Intent and confirmed both present; `deleteInFlight` still true | Server is gone and destroy is authorized; keeping auth guarded until destroy is harmless | Restart routes directly to `DeleteIncomplete`; tokens are no longer needed for proof. |
| `destroy()` retires both markers after durable vault unlink | Markers absent; vault/auth absent | Deletion complete | Restart routes to onboarding. |
| Intent write fails before server request | `deleteInFlight` eventually false; marker may be absent, or may physically exist despite unconfirmed fsync | No server request was sent. A surviving marker conservatively causes reconcile on a later unlock | No unauthorized local destroy: intent never authorizes destroy. |
| Concurrent `create()` | Existing vault makes create fail; after confirmed destroy, create first durably clears/re-stats stale markers | A successor cannot normally overlap a pending delete marker | Image lock serializes marker operations; no new round-16 race found. |

| Reader | Assumption | Holds for all writer states? |
|---|---|---|
| `onSessionRevoked`: `deleteInFlight || intentMarkerPresent()` | False means token clearing cannot break pending-delete recovery | **No.** `File.exists()==false` also represents an indeterminate stat while intent is present. Otherwise the union covers coroutine start through marker retirement. |
| Splash `serverDeleteConfirmed()` | Only confirmed-marker presence authorizes auto-destroy | Yes in the safe direction: an indeterminate `exists()==false` withholds destruction. |
| Post-unlock `deleteIntentPending()` | Intent-only triggers authenticated reconciliation | Conservative but can delay reconciliation on an indeterminate intent stat; it never authorizes destruction. |
| `destroy()` | Confirmed state authorizes unlink and marker retirement | Production callers reach it only after confirmed marker/proof; holds. |
| `create()` marker-absence proof | No prior marker may coexist with successor vault | Holds: round-15 `Files.notExists()` tristate checks remain fail-closed and image-lock serialized. |

## State coverage beyond the author's enumeration

- **Multiple rapid revoke events:** clean except the finding. While the marker can be read present, every event returns without clearing. After confirmed destroy, the vault/auth are gone. An event whose stat is indeterminate can pass the guard.
- **Concurrent create:** clean. The old image prevents creation during a pending delete; marker checks and writes share `imageLock`. Creation after destroy uses the previously reviewed fail-closed tristate absence proof.
- **Marker partial/corrupt contents:** clean. Markers are zero-byte existence signals; no content is parsed. A crash before directory-fsync may leave the intent absent or present. No server request is issued until the required intent write reports durable, and a surviving unconfirmed intent only causes conservative reconciliation.
- **Confirmed-marker creation before its fsync result:** clean. Guarding on raw intent-file presence, rather than `intent && !confirmed`, correctly protects auth even if the confirmed file is visible now but disappears after crash.
- **Marker retirement during revoke:** clean. `deleteInFlight` remains true through the synchronous `onConfirmed` teardown/destroy callback, covering the interval after intent unlink but before deletion coroutine completion.
- **Process restart with intent:** clean when the stat succeeds. No RAM re-derivation is needed because each revoke reads the shared `VaultImageStore` path directly.

## Auth/token writer inventory

| Path | Effect | Round-16 assessment |
|---|---|---|
| `onSessionRevoked()` → `ApiClient.clearTokens()` | Clears access/refresh tokens, retains account id | Only live external token-clear path. Guard lifetime is now correct, but its disk read fails open on indeterminate status (finding). |
| `ApiClient.deleteSession()` `finally` → `clearTokens()` | Clears access/refresh tokens | No production call site; still unguarded dead code, not a reachable finding at this head. |
| `ApiClient.deleteAccount()` | Classification only | Does not clear tokens/account. Clean. |
| `onAuthExpired()` | Starts re-authentication | Does not clear tokens/account. Clean. |
| `EncryptedAuthStore` / `VaultAuthStore` `clearTokens()` | Underlying implementations | Production mutation is reached through `ApiClient.clearTokens()` above; no independent caller. |
| `clearAccount()` and `accountId=null` | Would clear account id | No production call site/assignment at this head. |
| Confirmed `VaultImageStore.destroy()` | Deletes entire vault including auth | Runs only after durable confirmed authorization; clean. |

## Honest micro-window assessment

The named check/clear window is real. A revoke can evaluate both guard terms false immediately before a delete coroutine sets `deleteInFlight=true`, then proceed to clear tokens after deletion begins. Because the check and clear are not one atomic transaction, the same interleaving exists even after the coroutine is scheduled.

Its consequence is genuinely fail-safe: if tokens clear before the DELETE request is built, the request is rejected and the server account remains live; if an authenticated request was already constructed or processed, the server may be gone but the encrypted local vault is retained and reconciliation becomes stuck. Neither branch destroys user data, exposes plaintext, or authorizes local destruction without confirmation. No additional successful-stat micro-window beyond this acknowledged check/clear race was found.

## Clean areas

- Durable guard lifetime across `DEFINITE_FAILURE`, `AMBIGUOUS`, confirmed-not-durable, successful confirmation/destroy, and process restart: **CLEAN when filesystem status is readable**.
- Fresh disk observation rather than cached state: **CLEAN**.
- Direct intent-file guard rather than `deleteIntentPending()`: **CLEAN** and necessary for the visible-but-not-yet-durable confirmed-marker state.
- No new performance concern of security significance: the stat is rare and lock-bounded; the failure semantics, not latency, are the issue.
