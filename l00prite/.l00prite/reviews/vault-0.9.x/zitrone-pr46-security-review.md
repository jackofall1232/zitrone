# Independent adversarial security review — zitrone PR #46

Reviewed `0c1c6e4...2f14e17` (39 files), with extra attention to the round-12 paths requested. Findings below are source-verified against commit `2f14e17`.

## Findings

### P1 — The round-12 pre-request marker can destroy the only local vault even though the server account was never deleted

**Locations:** `apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:1326-1346`; `apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:1099-1121`; `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:627-696`

This is a **new defect introduced by the round-12 fix** (`99dce4b`). `deleteAccountAndWipe` durably creates `vault.destroy-pending` before calling `api.deleteAccount()`. However, every consumer of that marker treats it as proof that server deletion has already completed: after restart, Splash routes directly to `DeleteIncomplete`, whose `LaunchedEffect` automatically calls `destroyVaultForAccountDeletion()` and unlinks the vault.

Concrete failure interleavings:

1. `persistDeleteIntent()` returns after fsyncing the marker.
2. The process/device dies before line 1346 sends the HTTP request. On restart, lines 1099-1121 see the marker and automatically destroy `vault.bin`/`vault.dek`. The server account was never deleted.

The same outcome occurs without a crash when `api.deleteAccount()` fails: line 1346 wraps the call in `runCatching` and ignores the result, then lines 1347-1352 proceed to local teardown and destruction. A timeout is ambiguous and needs reconciliation, but a definite offline/DNS/TLS/HTTP failure is currently treated as success too.

The user permanently loses the identity keys and all local cryptographic state while the relay account can remain live, including queued messages and published prekeys. The UI then presents onboarding as successful account deletion. This is silent destructive data loss and leaves an undeletable/orphaned server identity.

Do not use one marker for both “delete requested” and “server deletion confirmed.” Persist a small durable phase/state machine. On boot, a pre-request/ambiguous phase must retry or reconcile the authenticated server delete while the vault is still available; only a durable `SERVER_DELETE_CONFIRMED` phase may enter the unlink-only `DeleteIncomplete` route. `api.deleteAccount()` must expose definite success versus definite failure/ambiguous transport outcome, and local destruction must not proceed on a definite failure.

### P1 — An `APPLIED_UNCONFIRMED` contact deletion is reported as complete and may durably resurrect after process death

**Locations:** `apps/android/app/src/main/java/com/zitrone/app/ZitroneApp.kt:694-731`; `apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:1203-1274`

The atomic mutation removes crypto records, roster entry, and adds the tombstone in RAM, but `sealDurableOrFalse` maps any storage/encoding failure after mutation to `APPLIED_UNCONFIRMED`. The coordinator then immediately burns the message history, clears transient state, invokes `onComplete`, and only launches a cancellable, bounded background flush retry. Lines 1239-1243 explicitly acknowledge that killing the process before a later successful flush restores the pre-delete sealed generation.

Concrete failure:

1. The in-memory delete applies.
2. `runtime.flushBeforeAck()` fails (for example ENOSPC, EIO, or an unconfirmed directory fsync), leaving the previous vault generation durable.
3. The UI closes the delete flow and local messages are irreversibly burned.
4. The retry is cancelled by lock/revocation, exhausts its attempts, or the process is killed.
5. Next unlock loads the old sealed blob: the roster entry and all Signal records return, while the message history that was burned after the failed flush does not.

This violates the stated invariant that deletion is permanently irreversible and that a deleted contact must never durably resurrect. A retry is not a commit protocol, especially when it is bounded and tied to the cancellable session scope.

The operation must not publish deletion success or burn history until durable commit is confirmed. If the storage API can enter a post-rename “applied but durability unknown” state, keep a separate crash-durable deletion intent/tombstone outside the replaceable vault generation (or use a recoverable journal) so boot can only roll forward. At minimum, the UI must remain in a terminal deletion state and prevent normal lock/teardown until a durable generation or durable roll-forward record exists; simply retrying in the background cannot uphold the invariant.

## Focus-area results with no additional findings

- **Lemon-drop binding / commit-before-render:** clean. The prompted `AwaitUnlock` value is captured and checked/CASed on Main before consumption and rendering; backgrounding and replacement-link races do not render plaintext under the wrong veil.
- **VaultImageStore unlink mechanics:** clean in isolation. It verifies primary and temp-file removal, fsyncs the directory before retiring the marker, and safely tolerates marker resurrection. The P1 above is the marker's lifecycle/meaning at its callers, not the verify-unlink implementation.
- **Prekey register-retry isolation and pending-upload markers:** clean. Private halves are flushed before publication, attempted one-time batches are not re-served after an ambiguous request, and signed-prekey pending state drives retry.
- **Inbound flush-before-ack:** clean. All post-decrypt branches, including receipts, unsupported controls, teardown, and duplicate redelivery, pass through the durable barrier before ack.
- **Key-material lifetime in the new vault unlock/session paths:** no additional wipe gap found. `VaultOpen`, biometric-unwrapped keys, construction copies, and session close paths are covered by `finally`/consume-or-wipe ownership.
