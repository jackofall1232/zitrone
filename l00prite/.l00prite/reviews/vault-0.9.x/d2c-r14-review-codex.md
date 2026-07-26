# Independent adversarial security review — Zitrone PR #46, round 14

Reviewed the round-14 production diff `71ec27b...5a136e9` in the supplied worktree, plus the round-13 adjudication. Crash/process death is assumed at every boundary.

## Findings

### P1 — WebSocket revocation can still durably clear authentication before `vault.delete-confirmed` exists

**Locations:** `apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt`, `deleteAccountAndWipe()` at lines 1363-1417 and `onSessionRevoked()` at lines 1796-1820

Round 14 removed auth clearing from `ApiClient.deleteAccount()`, but it did not make the claimed ordering global. `onSessionRevoked()` runs on the socket callback thread, outside the coordinator's `confined` serialization, and synchronously calls `api.clearTokens()` at line 1806. It can therefore race the non-cancellable `deleteAccountAndWipe()` while that flow has only the intent marker.

Concrete interleaving:

1. `deleteAccountAndWipe()` durably writes `vault.delete-intent`.
2. Its authenticated DELETE is in flight or has deleted the server account, but line 1406 has not yet durably written `vault.delete-confirmed`.
3. The socket reports `session.revoked`; `onSessionRevoked()` clears the tokens. Its `onForcedLogout` callback locks the live session, and `runtime.close()` can durably reseal that cleared-token state.
4. The process dies before the confirmed marker becomes durable.
5. On restart, intent-only correctly routes to unlock/reconcile, but the retry DELETE has no access token and receives 401 (`DEFINITE_FAILURE`). The intent remains forever and the confirmed marker can no longer be obtained through the designed authenticated retry.

This race is particularly relevant during account deletion because server-side deletion/session invalidation can itself cause the socket revocation. It violates the requested global invariant that auth survives until the confirmed marker is durable; checking only `ApiClient.deleteAccount()` was insufficient. The result is the same broken roll-forward class as F1: a provably deleted server account can leave a surfaced local vault indefinitely, now because another auth-clear call interleaved before the durable phase transition.

### P2 — The marker “re-stat” still treats an indeterminate stat as confirmed absence

**Location:** `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt`, `create()` at lines 394-408, `clearDeleteIntent()` at lines 662-669, and `clearBothMarkersDurably()` at lines 679-684

Round 14 added post-delete checks, but all checks use `java.io.File.exists()`. That API returns `false` both when a path is absent and when existence cannot be determined because of an I/O/access failure. Consequently these methods do not actually propagate every re-stat failure; they collapse one failure outcome into “marker absent.”

For `create()`, the destructive case is:

1. A stale `vault.delete-confirmed` exists.
2. `clearBothMarkersDurably()` attempts unlink; the unlink fails or its result is indeterminate.
3. The directory fsync succeeds, while the subsequent `serverDeletedFile.exists()` returns false because the stat cannot be completed rather than because the marker is absent.
4. The helper returns true and `create()` writes the successor vault.
5. When the filesystem becomes readable again, the old confirmed marker is present beside the successor; Splash routes to `DeleteIncomplete` and auto-destroys the valid new vault.

The new ordering is otherwise correct: marker retirement runs before any `vault.dek`/`vault.bin` write, and a visible surviving marker or a `NOT_DURABLE` fsync aborts with no successor image. The remaining defect is specifically the non-tristate re-stat. The same ambiguity means `clearDeleteIntent()` and the shared destroy-retire helper can still report success when their verification stat failed, contrary to the round-14 claim that re-stat failures propagate.

## Writer/reader invariant table

| Marker | Writer / transition | Durable meaning after a successful return | Mid-write crash states and meaning |
|---|---|---|---|
| `vault.delete-intent` | `VaultImageStore.markDeleteIntent()`; called first by `deleteAccountAndWipe()` | Delete was initiated; server outcome is unknown. Never authorizes local destruction. | Marker absent: request was not permitted to start, or crash occurred before the marker became durable. Marker present: at least intent exists; server may be live or gone. Both are handled conservatively. |
| `vault.delete-confirmed` | `VaultImageStore.markServerDeleteConfirmed()`; called only after `ApiClient.deleteAccount()` returns 2xx/404 | Server account is confirmed gone; local destroy is owed and authorized. | Crash before/during marker durability can leave it absent, in which case the intent remains and reconcile is required. If the file survives, its semantic claim is still true because the server result preceded its creation. |
| `vault.delete-confirmed` | `VaultImageStore.destroy()` calls `writeDurableMarker()` before unlinking vault files | Destroy is in progress for an already-confirmed server deletion; restart must roll forward. | Production callers reach `destroy()` only from `onConfirmed` after the required marker write, or from `DeleteIncomplete` after reading the confirmed marker. Thus this second writer inherits a valid confirmed-gone precondition. A crash after its durable write safely re-enters `DeleteIncomplete`. |
| Both markers, retirement | `clearBothMarkersDurably()` from `destroy()` | Vault files are already durably absent; neither phase marker remains. | A crash before durable retirement leaves one/both markers and no vault, causing an idempotent destroy retry. This is safe. Finding P2 applies when `File.exists()` misreports an indeterminate stat as absence. |
| Both markers, pre-create retirement | `clearBothMarkersDurably()` from `create()` | No stale phase marker from a prior occupant may coexist with the successor vault. | If the clear or fsync visibly fails, create stops before writing vault bytes. A crash after confirmed durable retirement but before/during create leaves no stale marker and either no image, a recoverable stray DEK, or a complete vault. Finding P2 is the indeterminate-stat exception to this proof. |
| `vault.delete-intent`, standalone retirement | `clearDeleteIntent()` | Intent is absent and its unlink is crash-durable. There is currently no production round-14 caller. | Visible survival or failed fsync throws. An indeterminate `File.exists()==false` can still falsely succeed (P2). |

| Reader | Assumption | Verified result |
|---|---|---|
| Splash routing (`MainActivity.kt:1125`) | Only `vault.delete-confirmed` authorizes `DeleteIncomplete`/auto-destroy. Intent-only routes to normal unlock. | **Holds.** Intent alone is never destroy authorization. |
| Post-unlock reconcile (`MainActivity.kt:1027-1030`) | Intent-only means the server outcome is unknown; retry authenticated DELETE rather than clear or destroy. | **Holds if auth remains available.** P1 shows the socket-revocation writer can invalidate that prerequisite before confirmation is durable. |
| Session-to-route reconciler (`MainActivity.kt:699-705`) | A confirmed marker with a null session means destruction is owed; otherwise an existing vault is lockable. | **Holds for every legitimate confirmed-marker writer.** Subject to P2 if a stale marker falsely survives pre-create verification. |
| `onRetryDestroy` (`MainActivity.kt:630-640`) | Entry via `DeleteIncomplete` means the server is confirmed gone and destroying the current image is safe. | **Holds for normal state transitions.** Subject to P2's stale-marker/successor coexistence. |
| `deleteIntentPending()` | Intent present and confirmed absent means “unknown outcome,” not “server live” or “server gone.” | **Holds.** Its readers reconcile rather than infer either terminal outcome. |

## Requested checks with no additional findings

- **Auth clearing inside `ApiClient.deleteAccount()`: CLEAN.** It now only classifies and returns; it does not mutate tokens/account on any result.
- **Required confirmed-marker write in `deleteAccountAndWipe()`: CLEAN locally.** Failure invokes `onConfirmedNotDurable` and returns without teardown. P1 is the concurrent `onSessionRevoked()` path outside that local sequence.
- **Create ordering and ordinary crash windows: CLEAN except P2.** Marker clearing precedes all vault bytes, and failed fsync/visible survival aborts before `newDek` generation or file writes.
- **Directory-fsync propagation:** CLEAN. `clearDeleteIntent()` checks `dirSync`; `clearBothMarkersDurably()` returns false on `NOT_DURABLE`; both callers turn failure into an exception. P2 concerns stat failure being represented as false/absent.
- **Intent-only auto-destroy prohibition: CLEAN.** No reader uses intent alone to destroy.
- **Confirmed-only auto-destroy authorization: CLEAN for valid filesystem observations.** Every production writer/caller preserves the confirmed-gone meaning; P2 is the stale-marker observation failure.

## “Already unusable token” residual

As narrowly stated—credentials were already unusable independently of this flow, the server may be gone, and the device has only an intent marker—the residual is correctly fail-safe and is primarily a UX/completion gap. The client cannot prove locally whether the server deleted the account: auto-destroying on intent would recreate the round-12 data-loss defect, while retaining and surfacing the vault preserves data until proof is available. No new silent resurrection or unauthorized destruction follows from that state alone.

P1 is distinct from that honest residual: it demonstrates code in this round can take usable credentials and clear them during the protected intent→confirmation interval, creating the unreconcilable state itself.
