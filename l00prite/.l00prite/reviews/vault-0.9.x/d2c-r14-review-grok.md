# Independent adversarial security review — PR #46 round 14

| Field | Value |
| --- | --- |
| Repo | `jackofall1232/zitrone` |
| Branch | `feat/0.9.1-vault-d2c-slot-a-live` |
| Head | `5a136e9` |
| Worktree | `/root/zitrone/.claude/worktrees/agent-a6bac19a3ea40ae4f` |
| Prior head (round 13) | `71ec27b` |
| Diff | `git diff 71ec27b...5a136e9` (7 files) |
| Context | `/root/l00prite/d2c-r13-adjudication.md` |
| Reviewer | Independent (Grok) — report only, no fixes |
| Threat model | Physical device + forensic capability; crash/kill at any point |

**Method:** Claims verified against source at `5a136e9`. No deference to the round-14 self-report or prior reviews except for defect IDs (F1/F2/F3/F4).

---

## Executive summary

| Adjudicated defect | Round-14 claim | Verified? |
| --- | --- | --- |
| **F1** (P1) — auth cleared before durable `delete-confirmed`; Splash abandoned intent | Required-durable confirmed marker; no auth clear in `deleteAccount()`; Splash keeps intent; post-unlock reconcile | **YES** for the primary path. One concurrent residual remains (session-revoke `clearTokens` race) — **P2** |
| **F2** (P2) — `create()` trusted `delete()` bool / cleared markers after vault write | Clear + dirSync + re-stat **before** any vault byte; fail with nothing on disk | **YES** |
| **F3/F4** (P3) — clear/retire ignored dirSync / trusted `delete()` bool | `clearDeleteIntent` fail-closed; `clearBothMarkersDurably` re-stat + dirSync; `destroy` throws on false | **YES** for the helpers; production no longer *calls* `clearDeleteIntent` (intent only retires via `destroy`) |

**No P1 found.** Round-13 F1’s silent re-registration path is closed on the primary crash roll-forward. The residual author residual (unusable token → vault retained) is correctly scoped as non-auto-destroy / recovery UX, with one concrete concurrent mechanism that can still strip tokens before the confirmed marker lands.

---

## 1. Auth clear before durable `vault.delete-confirmed`?

### Primary `deleteAccountAndWipe` path — **CLEAN**

| Step | Code | Auth mutation? | Marker? |
| --- | --- | --- | --- |
| 1 | `persistDeleteIntent()` (`MessagingCoordinator.kt:1368–1379`) | No | Intent required-durable or abort |
| 2 | `api.deleteAccount()` (`:1383–1389`, `ApiClient.kt:355–371`) | **No** — classify only | — |
| 3a | Non-`CONFIRMED_GONE` → `onNotConfirmed` (`:1390–1396`) | No; **intent kept** (no `clearDeleteIntent`) | Intent remains |
| 3b | `persistServerDeleteConfirmed()` **required** (`:1405–1415`) | No; on failure → `onConfirmedNotDurable`, session+auth kept | Confirmed only if durable |
| 4 | Teardown + `onConfirmed()` only after 3b succeeds (`:1417–1427`) | RAM/ws only until finishUi | Confirmed already durable |
| 5 | `completeTerminalWipe` → `signalStore.wipe()` + `destroy()` (`MainActivity.kt:964–981`) | Wipe/reseal/destroy **after** confirmed durable | Destroy unlinks; retires both markers |

`ApiClient.deleteAccount()` (`ApiClient.kt:347–371`) no longer calls `clearTokens()` / `clearAccount()`.  
`clearAccount()` has **no production call sites** left (only interface/impl definitions in `AuthStore.kt`).

Crash after step 2 (`CONFIRMED_GONE`) and before step 3b durable: intent present, auth intact → Splash unlock → reconcile `onDeleteAccount()` → idempotent 404 → confirm → destroy. That is the F1 roll-forward.

Crash after 3b, before destroy completes: `serverDeleteConfirmed()` true → `DeleteIncomplete` auto-destroy (does not need tokens).

### Surviving path that can still clear tokens before confirmed is durable — **P2**

**Finding R14-1 (P2)** — concurrent `onSessionRevoked` can clear tokens after server delete, before durable confirmed

**Where**

- `MessagingCoordinator.onSessionRevoked` (`:1796–1806`) — `api.clearTokens()` on the socket callback thread  
- Window: after `deleteAccount()` returns `CONFIRMED_GONE` (`:1383–1397`) and **before** `persistServerDeleteConfirmed()` returns successfully (`:1405–1415`)  
- Session is still fully live in that window (ws not disconnected until `:1419–1422`)

**Mechanism**

1. Intent durable; authenticated DELETE succeeds → server account gone → `CONFIRMED_GONE`.  
2. Relay tears down the session / sends revoke (common after account purge).  
3. `onSessionRevoked` runs **outside** the NonCancellable delete coroutine and **clears vault-backed tokens** (coalesced mutate; may flush within ≤2s).  
4. Crash (or process death) before `vault.delete-confirmed` is durable.  
5. Restart: intent present, confirmed absent → Splash → Locked (no auto-destroy). Auth tokens missing or empty.  
6. Unlock: `createSession` (identity-signed) fails against a deleted account; reconcile DELETE goes out **without** a bearer (`request()` only attaches `Authorization` when `accessToken != null`) → **401 → `DEFINITE_FAILURE`**.  
7. Intent is **kept** (correct — no abandon), but every unlock re-fails: vault **retained after a completed server delete**, with no path to `CONFIRMED_GONE` without usable auth.

This is narrower than round-13 F1 (no systematic clear inside `deleteAccount()`, no Splash intent wipe, **no `accountId` clear** → no silent re-registration). It is still a real roll-forward break when revoke races the confirm write.

**Not counted as the round-13 P1:** `accountId` is not cleared by revoke; boot loop does not re-register. Residual is retention + stuck reconcile, not identity re-bind.

---

## 2. Can a fresh vault coexist with a stale `vault.delete-confirmed`?

### `create()` — **CLEAN** for F2

`VaultImageStore.create` (`:395–408`):

1. Under `imageLock`, if either marker exists, call `clearBothMarkersDurably()`.  
2. If clear returns false → throw `NotDurable` **before** `randomBytes` / DEK write / `vault.bin`.  
3. Only then write dek/bin with existing durability barriers.

`clearBothMarkersDurably` (`:679–683`):

```text
delete both markers
dirSync must be DURABLE
re-stat both ABSENT
```

Silent `File.delete()==false` with marker still present → re-stat fails → false → create fails with **no image**.  
Crash mid-clear: at most partial marker unlink, **no** successor vault → no DeleteIncomplete-over-new-vault.  
Crash after successful clear + during vault write: markers already proven absent+durable; partial vault is MissingImage/retry territory, not “confirmed + new vault.”

### Residual assumption

Correctness still depends on `dirSync == DURABLE` meaning the marker unlinks will not journal-resurrect after a later vault write. That is the same global dirSync contract used everywhere else; not a new ordering bug.

---

## 3. Do clear/retire helpers propagate failures?

| API | Behavior | Propagates failure? |
| --- | --- | --- |
| `clearDeleteIntent()` (`:662–669`) | re-stat present after delete **or** dirSync ≠ DURABLE → throw `DestroyFailed` | **Yes** |
| `clearBothMarkersDurably()` (`:679–683`) | returns `false` unless dirSync DURABLE **and** both re-stat absent | **Yes** (boolean) |
| `create()` (`:404–407`) | `!clearBothMarkersDurably()` → throw `NotDurable` | **Yes** |
| `destroy()` (`:752–754`) | `!clearBothMarkersDurably()` → throw `DestroyFailed` | **Yes** |

No silent “success” on partial marker retire in these paths.

**Note:** `clearDeleteIntent()` is **unused in production** after round 14 (intent is never abandoned; only `destroy()` retires markers via `clearBothMarkersDurably`). F3 is correctly implemented on the API, but the live retire path is the shared helper only. That is not a defect.

---

## 4. WRITER / READER invariant table (post–round 14)

### `vault.delete-intent`

| Writer | Intended meaning | Durable-before-next-reader? |
| --- | --- | --- |
| `markDeleteIntent` / `writeDurableMarker` (`:648–649`, `:687–695`) | Delete **initiated**; server outcome unknown; **never** authorises auto-destroy | Yes — throw if not durable |
| `clearBothMarkersDurably` (from `destroy` / `create`) | Intent retired (destroy complete or pre-create hygiene) | Yes — false/throw if re-stat or dirSync fails |
| ~~Splash / DEFINITE_FAILURE clear~~ | — | **Removed** (round 14) |

| Reader | Assumption | Holds for every writer/crash state? |
| --- | --- | --- |
| `deleteIntentPending()` (`:772–773`) = intent∧¬confirmed | Intent-only mid-delete | **Yes** |
| Splash (`MainActivity.kt:1125–1132`) | Intent-only → normal unlock, **not** auto-destroy, **not** clear | **Yes** |
| Post-unlock reconcile (`MainActivity.kt:1027–1030`) | Intent-only → retry authenticated DELETE | **Yes if tokens usable**; **degraded** if tokens cleared by revoke/expiry (R14-1 / residual) — does not falsely auto-destroy |
| Boot `accountId == null` → register | Fresh install | **Yes on primary F1 path** (accountId not cleared early). Only after confirmed + wipe/destroy, when intent is gone / confirmed drives DeleteIncomplete |

### `vault.delete-confirmed`

| Writer | Intended meaning | Durable-before-next-reader? |
| --- | --- | --- |
| `markServerDeleteConfirmed` / coordinator step 3b (`MessagingCoordinator.kt:1405–1415`) | Server **provably** gone; local destroy **owed** | Yes — required-durable; failure → no teardown |
| `destroy()` pre-unlink (`VaultImageStore.kt:713`) | Same (idempotent if already present) | Yes — abort before unlink if not durable |
| `clearBothMarkersDurably` | Confirmed retired after files gone (or pre-create) | Yes — fail closed |

| Reader | Assumption | Holds? |
| --- | --- | --- |
| Splash / session reconciler / delete `finally` (`MainActivity.kt:703`, `:1003`, `:1125`) | confirmed → DeleteIncomplete only | **Yes** |
| `onRetryDestroy` / `destroy()` | Safe to destroy any remaining image | **Yes** when confirmed is honest (server gone). **Yes** for create: stale confirmed cannot coexist with a successfully returned create |
| Auto-destroy over successor vault | Must not see confirmed + foreign vault | **Yes** given create’s pre-write clear+re-stat |

### Crash-point roll-forward matrix (`deleteAccountAndWipe`)

| Crash after… | On-disk markers | Auth | Boot route | Recovery |
| --- | --- | --- | --- | --- |
| Intent write fail | none | intact | Locked/Onboarding | User retries delete |
| Intent durable, before/during DELETE | intent | intact | Locked + reconcile | Retry DELETE |
| `CONFIRMED_GONE`, confirm not durable | intent | **normally** intact | Locked + reconcile | 404 → confirm → destroy |
| Same + **R14-1 revoke cleared tokens** | intent | tokens gone | Locked + reconcile | 401 loop; vault retained (**P2 residual**) |
| Confirm durable, before destroy done | confirmed (±intent) | may still be on vault | DeleteIncomplete | Auto/manual destroy |
| Destroy files gone, marker retire fail | confirmed, no vault | n/a | DeleteIncomplete | Retry destroy (safe stuck) |
| Destroy fully success | none | destroyed with vault | Onboarding | Done |

---

## 5. Author “honest residual” — UX gap or invariant violation?

**Claim:** server gone in substance, local access token unusable → reconcile cannot complete → vault remains surfaced/undeleted indefinitely rather than auto-destroyed.

**Verified**

- Intent-only never auto-destroys (Splash `:1126–1130`) — intentional.  
- Reconcile documents token-less DELETE → 401 → `DEFINITE_FAILURE` → intent kept (`MainActivity.kt:1025–1026`).  
- Primary path retains tokens until destroy; **accountId is not cleared early** → boot does **not** silent-re-register under the old identity (the round-13 F1 worst case).  
- Auto-destroy still requires `vault.delete-confirmed` only.

**Verdict:** Correctly scoped as a **recovery / product residual**, not a two-marker invariant break and not the round-13 re-registration P1.

- **Not** an invariant violation of “intent-only must never auto-destroy” or “confirmed is the only auto-destroy authorisation.”  
- **Is** a remaining **retention-after-requested-delete** gap when auth for the idempotent 404 is gone (expiry, or **R14-1 revoke race**).  
- Forensic adversary with the device still sees a passphrase-gated vault until destroy eventually succeeds by other means — same as any incomplete delete, not a new disclosure of a second slot.

Severity: treat **R14-1** as the concrete, demonstrable instance (P2). Bare token-expiry without revoke is the same class (P2 residual / UX), not a new marker-semantics bug.

---

## Findings list

### P2 — R14-1: `onSessionRevoked` can clear tokens between `CONFIRMED_GONE` and durable confirmed marker

- **Files:** `MessagingCoordinator.kt:1796–1806` (`clearTokens`), window `MessagingCoordinator.kt:1383–1415`  
- **Defect:** Concurrent session revoke after a successful server account delete can strip vault-backed access tokens before `vault.delete-confirmed` is durable. Crash then leaves intent-only + tokenless vault; reconcile cannot reach 404; local destroy never auto-runs; no silent re-registration but permanent incomplete wipe.  
- **Relation to F1:** Primary F1 fix holds; this is a **new concurrent residual** on the still-live session between steps 2 and 3b.

### Residual / informational (not filed as P1)

- **Token-unusable reconcile stall** (author residual): confirmed as UX/recovery gap; marker invariants hold; no auto-destroy of a possibly-live server account.  
- **`clearDeleteIntent` unused in production:** F3 implementation is correct; live code path is `clearBothMarkersDurably` only.  
- **Stale kdoc** on `deleteIntentPending` (`VaultImageStore.kt:767–770`) still mentions clearing intent on boot — documentation drift only.

### Explicit CLEAN (looked)

| Check | Result |
| --- | --- |
| F1 primary ordering (no auth clear in `deleteAccount`; required-durable confirmed; no Splash intent abandon) | **CLEAN** |
| F2 create pre-write clear + re-stat | **CLEAN** |
| F3/F4 fail-closed marker retire helpers | **CLEAN** |
| Intent-only never auto-destroys | **CLEAN** |
| Confirmed-only auto-destroy | **CLEAN** |
| Silent re-registration after crashed delete (accountId wiped early) | **CLEAN** on primary path |
| Successor vault + stale confirmed coexistence after successful `create()` | **CLEAN** |

---

## Adjudicated F1/F2/F3/F4 vs round 14

| ID | Round-13 status | Round-14 status (this pass) |
| --- | --- | --- |
| F1 | CONFIRMED P1 | **Fixed** on the designed crash roll-forward; **P2 residual** R14-1 (revoke race) |
| F2 | CONFIRMED P2 | **Fixed** |
| F3 | CONFIRMED P3 | **Fixed** (API); unused by production callers |
| F4 | Informational | **Fixed** via shared re-stat helper in `destroy()` |

---

## Bottom line

Round 14 does what the adjudication asked for on F1/F2/F3/F4 at the marker state-machine level. The structural round-12/13 failure mode (reader assumes a meaning that a writer/crash state does not imply for **auto-destroy**) is closed for the two markers.

One real **P2** remains: **token clear outside the delete state machine** (`onSessionRevoked`) can still defeat authenticated reconcile after the server is gone but before `vault.delete-confirmed` is durable. That does not reintroduce silent re-registration or intent-driven auto-destroy of a live-account vault.

**No merge-blocking P1 identified in this pass.** R14-1 is the only severity-ranked defect; residual retention when auth is unusable is documented and correctly non-auto-destructive.
