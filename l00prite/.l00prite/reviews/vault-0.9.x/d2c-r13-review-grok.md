# Independent adversarial security review — PR #46 round 13

| Field | Value |
| --- | --- |
| Repo | `jackofall1232/zitrone` |
| Branch | `feat/0.9.1-vault-d2c-slot-a-live` |
| Head | `71ec27b` |
| Prior head (baseline for this pass) | `2f14e17` |
| Diff | `git diff 2f14e17...71ec27b` (7 files, +427/−221) |
| Reviewer | Independent pass (Grok) — parallel, uncoordinated |
| Threat model | Physical device + forensic capability; crash/kill mid-flow; journal-replay; adversarial concurrency |

**Scope:** Round-13 changes only. Claims verified against source at `71ec27b`, not against the PR description.

---

## Executive summary

Round 13 **does implement** the described two-marker state machine, CONFIRMED_GONE branching, durable-gated peer burn, and render-gated lemon-drop consume. The prior P1-A/P1-2 (intent marker auto-destroy / successor-vault wipe) and P2-1 (consume-before-render permanent loss) appear fixed in the production paths.

**One new residual** from the round-13 rewrite is real and actionable: auth tokens / account id are cleared inside `ApiClient.deleteAccount()` on `CONFIRMED_GONE` **before** a durable `vault.delete-confirmed` marker exists and before local destroy runs. A crash (or throw) in that window strands a still-present vault against a gone server account, with no clean auto-destroy path and a brittle re-delete path. Severity **P2**.

No Base64/minSdk false positives. No hallucinated compile issues.

---

## Verification of stated fixes

### Two-marker state machine (P1-A / P1-2) — **implemented**

| Claim | Source check | Verdict |
| --- | --- | --- |
| `vault.delete-intent` written first, before server call | `MessagingCoordinator.kt:1359–1374` → `persistDeleteIntent()`; `VaultImageStore.kt:649–650` `DELETE_INTENT_FILE` | **Holds** |
| Intent alone never auto-destroys | Splash: `serverDeleteConfirmed()` only → DeleteIncomplete; intent branch clears intent and routes Locked/Onboarding (`MainActivity.kt:1098–1110`). `deleteIntentPending()` requires confirmed **absent** (`VaultImageStore.kt:754–755`) | **Holds** |
| `vault.delete-confirmed` only after definite gone | Written by coordinator after `CONFIRMED_GONE` (`MessagingCoordinator.kt:1394–1396`) and again at start of `destroy()` (`VaultImageStore.kt:687–694`) | **Holds** |
| Auto-destroy only when confirmed | `MainActivity.kt:700–703`, `1098`, `1125–1126`; `onRetryDestroy` success = `!hasVault() && !serverDeleteConfirmed()` (`MainActivity.kt:632–633`) | **Holds** |
| Local destroy only on `CONFIRMED_GONE` | `MessagingCoordinator.kt:1385–1407`: non-confirmed → `onNotConfirmed`, return; no RAM teardown, no `onConfirmed` | **Holds** |
| Tokens kept on non-confirmed | `ApiClient.deleteAccount()` clears tokens **only** inside `if (result == CONFIRMED_GONE)` (`ApiClient.kt:371–375`) | **Holds** for DEFINITE_FAILURE / AMBIGUOUS returns |
| `destroy()` retires both markers crash-durably | Delete both, then `dirSync` must be `DURABLE` or throw (`VaultImageStore.kt:732–736`) | **Holds** |
| `create()` clears stale markers crash-durably | After vault durable write; delete both + required `dirSync` (`VaultImageStore.kt:438–452`) | **Holds** (order residual noted below as P3) |

### `AccountDeleteResult` branching — **implemented**

`ApiClient.kt:334–376`:

- 2xx → `CONFIRMED_GONE`
- 404 → `CONFIRMED_GONE` (idempotent already-gone)
- 5xx → `AMBIGUOUS`
- other 4xx → `DEFINITE_FAILURE`
- `IOException` (transport) → `AMBIGUOUS`

Coordinator (`MessagingCoordinator.kt:1378–1392`):

- Not `CONFIRMED_GONE` → no destroy; `DEFINITE_FAILURE` clears intent (best-effort); `AMBIGUOUS` leaves intent; `onNotConfirmed` lifts terminal-wipe gate; session stays live.

### Durable-gated contact peer burn (P1-B) — **implemented**

`MessagingCoordinator.kt:1226–1280`:

- Local `burnAll(..., notifyPeer = false)` on any applied outcome (RAM cleanup).
- `ws.burnMessage` **only** on `DURABLE` immediately, or after background flush confirms on `APPLIED_UNCONFIRMED`.
- Unconfirmed exit: peer left un-burned (consistent with possible resurrection).

### Render-gated lemon-drop consume (P2-1) — **implemented**

`MainActivity.kt:261–301`:

1. On Main: `activityStarted && CAS(AwaitUnlock → Delivered)` — refuse ⇒ return, **nothing consumed**.
2. Only after successful CAS: `deliverDurablyCommit` then `burn` on `DURABLE`.

Backgrounded / veil-stolen before CAS: no consume, re-scannable. Process death after CAS before durable consume: documented double-open residual (milder than permanent loss). Round-12 “no plaintext behind stopped Activity” preserved via Main-serialized `onStop` → `clearDelivered()`.

---

## Findings

### P2-1 — NEW: tokens/account cleared on `CONFIRMED_GONE` before durable `delete-confirmed` / local destroy

**Where**

- `ApiClient.kt:371–375` — on `CONFIRMED_GONE`, immediately `clearTokens()` + `authStore.clearAccount()` + RAM `_accountId = null`
- `MessagingCoordinator.kt:1394–1407` — confirmed marker is **best-effort** (`runCatching`), then session teardown + `onConfirmed()` → destroy
- Splash recovery (`MainActivity.kt:1098–1110`) — without `serverDeleteConfirmed()`, routes to unlock and **clears intent**

**Failure mode**

After the server has definitely deleted the account, the client clears vault-backed auth **before** a crash-durable `vault.delete-confirmed` exists:

```text
HTTP delete → CONFIRMED_GONE
  → clearTokens() / clearAccount()     // vault mutates; may flush within ≤2s coalescing
  → runCatching { markServerDeleteConfirmed() }  // best-effort
  → onConfirmed() → destroy()          // also writes confirmed, then unlinks
```

Crash (or unexpected throw from the clear mutates) in the window after the server is gone and before a durable confirmed marker + successful destroy:

1. Server account: **gone**.
2. Local vault: still present (full crypto / roster may remain).
3. `vault.delete-confirmed`: **absent**.
4. `vault.delete-intent`: may still be present → Splash **clears** it and offers **Locked**, not DeleteIncomplete.
5. Re-login via `createSession` fails if `accountId` survived (account deleted server-side).
6. Retry delete often goes out **without** a bearer token (`request()` only attaches `Authorization` when `accessToken != null`). Server returns **401** → classified as `DEFINITE_FAILURE` → **no local destroy**, intent cleared again.

Net: **stranded local vault** over a deleted server identity, no auto-destroy authorization, brittle manual recovery. This is the opposite corner of the old P1 (auto-destroy while server live): round 13 correctly refused that, but left “server gone, local not destroyed, auth already wiped” underspecified.

**Why this is new in round 13**

Pre-round-13 always destroyed after the (swallowed) delete call. Round 13 correctly gated destroy on `CONFIRMED_GONE` but kept token clear **inside** the API method at confirmation time, rather than after durable confirmed marker / destroy.

**Fix direction (concrete)**

1. **Do not** clear tokens/account inside `deleteAccount()` on success.
2. Order in coordinator after `CONFIRMED_GONE`:
   - `persistServerDeleteConfirmed()` **required-durable** (fail → still call destroy path / DeleteIncomplete; do not treat as AMBIGUOUS),
   - then clear tokens/account (or clear only after `destroy()` succeeds),
   - then `onConfirmed()` teardown + destroy.
3. Recovery: if `delete-intent` or a “server-gone” sticky flag is set and delete returns 401/404 with no tokens, still allow local destroy / DeleteIncomplete rather than DEFINITE_FAILURE abandon.

---

### P3-1 — `create()` does not re-stat markers after clear; clear-after-vault order is inverted vs. the documented threat

**Where:** `VaultImageStore.kt:438–452`

**Failure mode**

Stale markers are cleared **after** the new vault’s durable write. If `File.delete()` leaves a marker present (returns false) but `dirSync` still reports `DURABLE`, create returns success with `serverDeleteConfirmed() == true` and a live image → next Splash → DeleteIncomplete → **auto-destroy of the new vault**.

Also, the crash window “vault durable, marker clear not yet durable” is exactly the successor-wipe scenario create is meant to close; clearing markers **first** (durable), then writing the vault, would eliminate that ordering residual.

Reachability is low if Splash never offers Onboarding while confirmed is set; still a defense-in-depth gap in the primitive that round 13 added for P1-2.

**Fix:** Clear + dirSync + **re-stat markers absent** before installing in-memory create success; prefer marker clear before vault write, or verify `!serverDeletedFile.exists() && !deleteIntentFile.exists()` after clear.

---

### P3-2 — `clearDeleteIntent()` ignores `dirSync` result

**Where:** `VaultImageStore.kt:657–664`

**Failure mode**

On `DEFINITE_FAILURE`, intent clear is best-effort; a non-durable clear can leave intent on disk. Intent alone never auto-destroys (Splash clears again). Security impact none; hygiene only. Prefer fail-closed or re-stat if intent-absence is ever load-bearing for UX/retry logic.

---

## Focus-area verdicts (looked)

| Area | Verdict |
| --- | --- |
| **Two-marker SM (intent vs confirmed)** | **CLEAN** for the auto-destroy authorization invariant. Intent ≠ destroy. Confirmed = only auto-destroy. |
| **CONFIRMED_GONE / DEFINITE_FAILURE / AMBIGUOUS branching** | **CLEAN** for “never local-destroy on non-confirmed.” **P2-1** on post-CONFIRMED auth wipe ordering. |
| **`destroy()` dual-marker retire + post-retire dirSync** | **CLEAN** vs. prior P1-2 (files-absent + marker resurrect over successor). |
| **`create()` stale-marker clear** | **Mostly CLEAN**; **P3-1** on order / re-stat. |
| **Tokens survive non-CONFIRMED_GONE return paths** | **CLEAN** when `deleteAccount()` returns DEFINITE_FAILURE / AMBIGUOUS normally. |
| **Contact-delete peer burn (P1-B)** | **CLEAN** — burns only after durable confirmation. |
| **Render-gated lemon-drop consume (P2-1)** | **CLEAN** for permanent-loss-before-show. Documented double-open residual accepted. |
| **No plaintext behind stopped Activity** | **CLEAN** — Main-thread `activityStarted` + CAS; `onStop` clears `Delivered` only (not AwaitUnlock). |
| **Prekey only after successful render** | **CLEAN** — consume is after CAS to Delivered. |

---

## Explicit non-findings

- **java.util.Base64 / minSdk 26:** not raised.
- **Prior P1-A (intent → auto-destroy):** fixed; Splash and reconciler key only on `serverDeleteConfirmed()`.
- **Prior P1-2 (marker retire without dirSync over successor vault):** fixed in `destroy()`; create adds belt-and-suspenders.
- **Prior P2-1 (DURABLE consume without render + process death):** fixed by render-first ordering.
- **Stale kdoc in `classifyDeliveryCommit` (“caller MUST still render”)** (`LemonDropRedeemer.kt` ~277–279): documentation drift only; call site is render-first. Not a runtime defect.

---

## Suggested fix priority

1. **P2-1** — Move token/account clear to after durable `delete-confirmed` (and ideally after destroy starts or completes); never map post-CONFIRMED local failures to AMBIGUOUS without ensuring DeleteIncomplete/local wipe can still run.
2. **P3-1 / P3-2** — Tighten marker clear primitives (re-stat, order, dirSync checked).

---

## Files touched this pass

`MainActivity.kt`, `MessagingCoordinator.kt`, `ZitroneApp.kt`, `VaultImageStore.kt`, `LemonDropRedeemer.kt`, `ApiClient.kt`, `VaultImageStoreTest.kt` (tests align with two-marker + post-retire dirSync behavior).
