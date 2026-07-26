# Independent adversarial security review — Zitrone PR #46, round 13

Reviewed only `2f14e17...71ec27b` against the source at `71ec27b`.

## Findings

### P1 — Confirmed server deletion is not made durable before authentication is discarded

**Locations:** `apps/android/app/src/main/java/com/zitrone/app/net/ApiClient.kt:354-376`; `apps/android/app/src/main/java/com/zitrone/app/MessagingCoordinator.kt:1378-1407`; `apps/android/app/src/main/java/com/zitrone/app/MainActivity.kt:1094-1110`

This is a **new round-13 state-machine defect**. `ApiClient.deleteAccount()` clears the account id and both tokens as soon as it receives 2xx/404 (lines 371-374). The coordinator then treats persistence of `vault.delete-confirmed` as best-effort (`runCatching` at line 1396), and the marker is not required to land before continuing.

Concrete crash window:

1. `vault.delete-intent` is durable.
2. The server deletes the account and returns 2xx (or 404 confirms it gone).
3. `ApiClient` clears the live authentication state.
4. The process/device dies before `persistServerDeleteConfirmed()` durably creates `vault.delete-confirmed`, or that write fails and the process dies before `onConfirmed` reaches `destroy()`.
5. On restart, only `vault.delete-intent` exists. Splash explicitly treats intent-only as an abandoned operation, asynchronously clears it, and routes to normal unlock. It never auto-destroys or resumes server reconciliation.

The server account is gone, but the device retains the vault without the sole durable authorization needed to finish destroying it. Because authentication was cleared before the confirmed marker became durable, the client also cannot reliably repeat the authenticated DELETE to obtain the idempotent 404 confirmation. Depending on which coalesced vault generation survived, the user gets a dead account behind the normal lock/session path or stale auth state that fails later; in neither case does the promised crash-safe roll-forward occur.

Make the server response and local phase transition recoverable in this order: `deleteAccount()` must return the classification without clearing account/tokens; on `CONFIRMED_GONE`, the coordinator must require `vault.delete-confirmed` to be written and directory-fsynced, then clear auth and proceed to teardown. If the process dies after the response but before the marker, intent plus retained credentials lets the next unlock/retry repeat DELETE and obtain 404. A failed confirmed-marker write must not be swallowed.

### P1 — `create()` can publish a successor vault while `vault.delete-confirmed` still exists

**Location:** `apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt:438-463` (same unchecked deletion pattern at `:657-664` and `:726-735`)

This is a **new defect in the round-13 marker-retirement fix**. After writing the new `vault.dek` and `vault.bin` durably, `create()` calls `File.delete()` for both stale markers but ignores both boolean results. It checks only whether the following directory fsync succeeds. `File.delete()` returns `false` on an unlink failure without throwing, so a successful fsync does not prove either marker is absent.

Concrete failure:

1. A stale `vault.delete-confirmed` exists from the prior account.
2. `create()` durably installs the successor account's vault.
3. Unlinking the confirmed marker fails (I/O/filesystem fault), but directory fsync succeeds.
4. `create()` returns and publishes the new live session because it never re-stats the marker.
5. On the next lock/restart, `serverDeleteConfirmed()` is still true, routing to `DeleteIncomplete`; its automatic retry calls `destroy()` and deletes the valid successor vault.

There is a second unsafe shape when marker deletion succeeds in the current namespace but its fsync is unconfirmed: `create()` throws only after the complete successor vault is already durable. The UI detects `hasVault()` and routes that image to normal unlock, while a crash can replay the old confirmed marker and later auto-destroy it.

Markers must be cleared, re-statted absent, and directory-fsynced **before any successor image is written**. If unlink or fsync cannot be confirmed, fail with no successor vault on disk. Apply the same verify-after-delete discipline to `clearDeleteIntent()` and `destroy()` so those methods do not claim durable retirement merely because `dirSync` succeeded after a silent unlink failure.

## Focus-area verification

- **Two-marker authorization semantics:** **CLEAN except for the two durability gaps above.** Intent-only is never consulted as destroy authorization. All automatic `DeleteIncomplete` routing checks only `serverDeleteConfirmed()`.
- **`CONFIRMED_GONE` / `DEFINITE_FAILURE` / `AMBIGUOUS` classification:** **CLEAN.** 2xx and 404 map to confirmed gone; other 4xx map to definite failure; 5xx and transport `IOException` map to ambiguous.
- **Non-confirmed branching and tokens:** **CLEAN.** Definite failure and ambiguous outcomes do not run local teardown/destruction, and `ApiClient` retains account/tokens on both. The finding above concerns premature token clearing at the not-yet-durable confirmed transition.
- **Destroy authorization on non-confirmed outcomes:** **CLEAN.** Neither `DEFINITE_FAILURE` nor `AMBIGUOUS` invokes `onConfirmed`, session teardown, or `destroy()`.
- **Marker retirement/clearing:** **NOT CLEAN.** Directory fsyncs were added, but silent unlink failure is not verified; `create()` also performs the clearing too late, after the successor image is already installed.
- **Render-gated lemon-drop consume:** **CLEAN.** The Main-thread `activityStarted` check and CAS to this drop's exact `AwaitUnlock` occur before `deliverDurablyCommit`; a stopped Activity or stolen veil returns without consuming the prekey.
- **No plaintext behind a stopped Activity:** **CLEAN.** Publishing `Delivered` and `onStop`'s `activityStarted=false`/veil clear are serialized on Main. If stop wins, rendering is refused; if render wins, the subsequent stop clears the plaintext-bearing veil.
- **Durable-gated peer burn:** **CLEAN.** `ws.burnMessage` runs immediately only for `DURABLE`, or after the deferred flush confirms; it is withheld for exhausted/cancelled `APPLIED_UNCONFIRMED` retries.

## Verification note

The targeted `VaultImageStoreTest` Gradle run could not start because this checkout has no configured Android SDK (`sdk.dir`/`ANDROID_HOME`). The findings above are direct source/control-flow results and do not depend on a test failure.
