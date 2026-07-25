# Independent adversarial security review — PR #46 (vault D2c slot-A live)

| Field | Value |
| --- | --- |
| Repo | `jackofall1232/zitrone` |
| Branch | `feat/0.9.1-vault-d2c-slot-a-live` |
| Diff | `git diff 0c1c6e4...2f14e17` (39 files) |
| Head | `2f14e17` |
| Base | `origin/main` (`0c1c6e4`) |
| Reviewer | Independent pass B (second parallel review) |
| Threat model | Physical device + forensic capability; crash/kill mid-flow; journal-replay FS; adversarial concurrency |

**Method:** Full PR surface read; heaviest weight on `MessagingCoordinator.kt`, `MainActivity.kt`, `VaultImageStore.kt`, `SignalProtocolManager.kt`. Claims verified against head sources (worktree at `2f14e17`), not against comments alone. No deference to prior review rounds.

**Severity:** P1 = exploit/loss under realistic adversary or crash windows that break stated security invariants; P2 = real loss/race under narrower conditions; P3 = correctness/hygiene that is not yet a clean security break.

---

## Executive summary

Three real defects. Two are in the **destroy-pending marker** lifecycle introduced/extended in recent fix rounds (especially round 12 intent-persist and the DeleteIncomplete auto-retry). One is in **lemon-drop commit-before-render** when combined with process death. The rest of the focus surface (inbound flush-before-ack, contact-delete atomic seal, prekey ATTEMPTED isolation, verify-unlink + unlink dirSync) looks intentionally designed and, where residual crash windows remain, they are documented and fail closed rather than silently acking loss.

---

## Findings

### P1-1 — Destroy-pending marker is written **before** server delete; boot auto-destroys a still-valid vault

**Where**
- `MessagingCoordinator.kt:1325–1352` — `deleteAccountAndWipe`
- `VaultImageStore.kt:627–639` — `markDestroyPending` / `writeDurableDestroyMarker`
- `MainActivity.kt:1103`, `1119–1120`, `648–655` — Splash → `DeleteIncomplete` + auto `onRetryDestroy`
- `ZitroneApp.kt:165–171` — `vaultDestroyPending` kdoc claims “server already deleted” (false under this ordering)

**Failure mode**

Round 12 intentionally persists `vault.destroy-pending` **before** `api.deleteAccount()` leaves the device:

```text
persistDeleteIntent()          // durable marker
runCatching { api.deleteAccount() }  // best-effort; failure swallowed
… clear RAM …
onComplete() → completeTerminalWipe → destroyVault()
```

Routing treats **any** present marker as “finish destroying the vault, never unlock”:

```text
vaultDestroyPending() → Route.DeleteIncomplete
LaunchedEffect(Unit) { onRetryDestroy() }  // automatic, no confirmation
```

Concrete sequences:

1. **Crash after marker, before server delete**  
   Marker is durable. Server account still exists. Vault files intact. Next cold start: Splash → DeleteIncomplete → auto `destroyVaultForAccountDeletion()` → local vault irreversibly destroyed. User cannot unlock. Server account is orphaned.

2. **Server delete fails (network / 5xx) after marker**  
   `runCatching { api.deleteAccount() }` swallows the error and **still** runs `onComplete()`, which tears down the session and destroys the local vault. Same orphan outcome without a crash.

3. **Kdoc lie**  
   `destroyPending()` / `vaultDestroyPending()` document “server account is already deleted.” After round 12 that is only sometimes true. Routing and auto-retry assume it always is.

**Why this is security-relevant (not just UX)**

- Silent irreversible destruction of the only local copy of Signal identity + ratchet + roster while the server-side account may still exist.
- No unlock path remains (marker overrides `hasVault()`).
- Auto-retry means the user never even confirms the wipe on the crash path.
- This is a **new residual introduced by the round-12 “persist intent first” fix**: earlier “marker only after server delete” risks Locked-over-dead-account; the new ordering risks Destroy-over-live-account. The second is worse for data integrity, and the auto-retry makes it automatic.

**Fix direction (concrete)**

- Split markers or states: e.g. `delete-intent` (abortable / unlockable) vs `server-deleted-pending-unlink` (DeleteIncomplete only).
- Or: write destroy-pending **only after** server delete returns success; accept a different recovery UX for the mid-flight crash (still better than auto-wiping a live vault).
- Never call `onComplete()`/local destroy if `api.deleteAccount()` did not confirm success (unless the user explicitly chose “wipe this device only”).
- DeleteIncomplete must not auto-destroy when the vault still opens under the user’s passphrase and the server account may still be live.

---

### P1-2 — Marker retirement is not crash-durable; a resurrected marker auto-destroys a **successor** vault

**Where**
- `VaultImageStore.kt:684–696` — unlinks are `dirSync`’d, then `destroyMarkerFile.delete()` with **no** trailing `dirSync`
- `VaultImageStore.kt:386–446` — `create()` never clears / refuses a stale destroy marker
- `MainActivity.kt:1103`, `1119–1120`, `648–655` — any marker presence → DeleteIncomplete → destroy **whatever** image is present

**Failure mode**

`destroy()` correctly makes vault file unlinks crash-durable **before** retiring the marker (round 8). It then retires the marker as best-effort:

```kotlin
if (dirSync(baseDir) != DirSyncResult.DURABLE) throw DestroyFailed()
// Unlinks confirmed durable — retire the marker. Best-effort…
destroyMarkerFile.delete()  // no dirSync after this
```

Comments assume a surviving marker only re-runs an **idempotent** destroy over an already-absent image. That is true only while no new vault exists.

Journal-replay sequence:

1. Account delete `destroy()` returns success (unlinks durable; marker unlink not durable).
2. User re-onboards; `create()` writes a **new** durable `vault.bin` / `vault.dek` (create’s own dirSync confirms).
3. Power loss / crash before the marker’s dentry deletion is durable.
4. Journal restores `vault.destroy-pending` while the **new** vault files remain.
5. Next boot: `vaultDestroyPending() == true` and `hasVault() == true` → DeleteIncomplete → auto `destroyVaultForAccountDeletion()` → **successor vault permanently destroyed**.

`create()` does not:
- refuse when `destroyPending()`, or
- clear + dirSync the marker as part of a successful create.

So the “marker surviving is safe / idempotent” claim is false after re-onboarding.

**Why this is a recent-fix residual**

Round 8 introduced the marker and explicitly accepted best-effort retirement (“a marker that survives just re-runs idempotent destroy”). That was safe only for the empty-image case. Round 11–12 strengthened marker **creation** durability and boot routing, which **amplifies** the damage when a stale marker coexists with a new image. Create path was never updated to close the loop.

**Fix direction (concrete)**

1. After `destroyMarkerFile.delete()`, require `dirSync(baseDir) == DURABLE` before treating destroy as success (mirror unlink barrier); if not durable, throw `DestroyFailed` and keep retrying (marker-present + files-absent is the safe stuck state).
2. `create()` must clear any destroy marker and dirSync that clear **after** the new vault is durable (or refuse create while marker present until an explicit “finish previous delete” path runs on an empty image only).
3. Boot routing: if `destroyPending && hasVault`, do **not** auto-destroy a vault that still decrypts — that state is “stale marker over live vault,” not “partial unlink.”

---

### P2-1 — Lemon-drop: durable prekey consume without render + process death = permanent silent message loss

**Where**
- `MainActivity.kt:261–323` — `openLemonDrop` commit-before-render + `activityStarted` gate
- `LemonDropRedeemer.kt:167–201` — `DeliveryCommit` contract (“APPLIED outcomes MUST render”)

**Failure mode**

Delivery deliberately consumes the one-time prekey and flushes **before** showing plaintext. That is correct for one-shot semantics **if and only if** an applied commit always becomes user-visible or the prekey remains recoverable.

`openLemonDrop` then refuses to render when the Activity is not started:

```kotlin
commit == NOT_APPLIED -> advocacy
container.activityStarted -> CAS to Delivered
else -> false   // no render, no burn
```

In-process, the comment is right: AwaitUnlock stays, user re-auths, commit re-runs idempotently.

Across **process death** after a `DURABLE` commit but before render:

1. User passes biometric; flush of prekey removal succeeds (`DURABLE`).
2. User backgrounds during/after flush (`activityStarted == false`) → no `Delivered`, no burn.
3. Process is killed (LMK, swipe-away, update). AwaitUnlock plaintext is process-scoped only (correct: never in savedInstanceState).
4. On relaunch, prekey is gone from the vault. Rescan/probe cannot open the drop → SEALED/UNAVAILABLE. Burn may never have fired; TTL eventually reaps the relay copy. **Plaintext is gone forever.**

This directly violates the contract written in `DeliveryCommit.APPLIED_UNCONFIRMED` / commit-before-render comments: “the message must RENDER now or risk being lost forever.” The Activity-started gate chooses auth hygiene over that invariant, and the recovery story only covers same-process re-auth, not process death.

Also: if a second `/d` link steals the veil mid-commit after consume, CAS fails and A is forfeited (documented residual). Process death after that forfeit has the same permanent loss.

**Severity rationale**

Not a key leak; silent permanent loss of a one-shot message under a realistic mobile lifecycle (background + kill) during a window the PR itself introduced (rounds 8–12 commit-before-render).

**Fix direction (concrete)**

Pick one invariant and uphold it:

- **Render-or-recover:** if commit is APPLIED/DURABLE and render is refused, persist a sealed “pending delivery” record (not plaintext in Bundle) that can be re-shown after the next biometric without needing the OTP; or
- **Consume-after-render:** show plaintext under the already-passed biometric, then consume+flush+burn (accept a narrower double-open window); or
- At minimum, on `DURABLE` + `!activityStarted`, still keep a process- **and** disk-durable “owed delivery” ticket so process death does not forfeit the only decrypt capability.

---

## Focus-area assessments

### `MessagingCoordinator.kt` — clean on the core paths; P1-1 lives here

| Area | Verdict |
| --- | --- |
| **Inbound flush-before-ack** (`ackDurable` / `flushThenAck`, including `DuplicateMessageException` ACK_AND_DROP) | **Clean.** Fail-closed on non-durable flush; transient retry; cancellation rethrown; duplicate path also flushes before ack (closes durable-but-unacked loop). |
| **Outbound flush-before-send** (`flushSendRatchet` outside the non-suspending check→send tail) | **Clean.** Delete-atomicity race with suspension between check and send is closed. |
| **Contact-delete burns/outcome** | **Clean** on the vault path: burns only after `outcome != NOT_APPLIED`; atomic seal under repo monitor; `APPLIED_UNCONFIRMED` background flush retry; documented residual resurrection if process dies before any successful flush (not silent “delete reported, crypto kept”). |
| **Prekey register-retry isolation** | **Clean.** Two-phase ATTEMPTED + flush before register; `registration = null` on ambiguous failure; `discardAttempted=true` only on register retry; top-up uses `discardAttempted=false` so attempted privates are **retained** for peers who may hold bundles (kdoc matches code). Register path flushes after `confirmPreKeysUploaded`. |
| **`deleteAccountAndWipe` intent-persistence** | **P1-1** (see above). NonCancellable + confined is fine; the ordering vs server delete is not. |

### `MainActivity.kt` — reconciler clean; lemon-drop + DeleteIncomplete not

| Area | Verdict |
| --- | --- |
| **Session↔route reconciler** | **Clean.** Null session derives route from disk truth (`destroyPending` / `hasVault`); matches delete `finally`; avoids ChatList-with-null-session and Locked-over-live-session after rotation. |
| **Account-delete routing / `completeTerminalWipe`** | Ordering (wipe → lock/reseal → destroy in `finally` → releaseGate) is correct for no-remanence **given** a correct destroy marker policy. Auto DeleteIncomplete path participates in P1-1 and P1-2. |
| **Lemon-drop drop-binding + commit-before-render** | CAS-to-prompted-drop binding is sound for multi-drop races. **P2-1** for process-death after durable consume without render. |
| **Biometric unlock wipe** | `unlockWithBiometric` wipes recovered key in `finally`; refused builds wipe VaultOpen; looks correct. |

### `VaultImageStore.kt` — verify-unlink clean; marker lifecycle not fully closed

| Area | Verdict |
| --- | --- |
| **Verify-unlink** (re-stat bin/dek/temps, throw `DestroyFailed`) | **Clean.** |
| **Unlink durability before marker retire** (`dirSync` after deletes) | **Clean** for the “files resurrect after marker gone” direction. |
| **Marker create durability** (`createNewFile` + dirSync, fail-closed) | **Clean** as a primitive. |
| **Marker retire + create interaction** | **P1-2.** Best-effort marker delete without dirSync + create ignoring marker + boot auto-destroy. |
| **Key wipe on open/create failure paths** | Looks disciplined (liveOpen wipe scopes, DEK wipe on close/destroy first). |

### `SignalProtocolManager.kt` — pending-upload markers **clean**

| Area | Verdict |
| --- | --- |
| Signed prekey pending upload id | **Clean.** Re-serve same record until confirm; age gate alone would miss retries. |
| OTP pending ids + ATTEMPTED | **Clean** for the double-serve threat. Top-up does **not** discard attempted privates (`discardAttempted` default false); register-retry does. Matches the kdoc at lines 203–217 / 234–241. |
| Confirm without immediate flush on top-up | Acceptable: crash leaves ATTEMPTED+pending on disk; next generate keeps old privates and mints a fresh batch (capacity orphans, not undecryptable peer messages). |

---

## Explicit non-findings (looked, not reported)

- **`java.util.Base64` / minSdk 26:** not an issue; used in several places, consistent with platform.
- **Import / compile hallucinations:** sources in this branch are coherent; not filing style-only nits.
- **Contact delete “burns before apply”:** vault path correctly burns **after** apply (MessagingCoordinator ~1217–1221). Stale comments elsewhere (e.g. ConversationRepository kdoc still saying peer-burn must run before seal) are documentation drift only.
- **Capacity overflow + flushBeforeAck refuse:** fail-closed; inbound stays un-acked. Residual that an never-fitting mutation is lost on close is documented and not an acked-loss bug.
- **Deniability / second slot evidence:** single biometric wrap blob; constant sizes; unlock router uniform failure. Slot index stored in biometric prefs is always the live slot for D2c; no second wrap. Not filing a deniability break for slot-A-only.

---

## Suggested fix priority

1. **P1-1 + P1-2 together** — treat as one destroy-marker state machine rewrite: when the marker may be written, what boot does, when it is retired durably, and how create interacts. Until this is fixed, account-delete + re-onboard under crash is unsafe.
2. **P2-1** — lemon-drop delivery must not permanently forfeit the only decrypt capability without a durable recovery path.

---

## Review hygiene

- Verified line numbers against head `2f14e17` sources.
- Did not re-run the Android unit suite in this pass; logic conclusions are from static adversarial reading of the production paths and the PR’s own host tests’ contracts.
- If a parallel review reports the Base64/minSdk item or invented compile errors, discount those; the defects above are independent and source-checked.
