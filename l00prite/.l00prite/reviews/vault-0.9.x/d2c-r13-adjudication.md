# Round-13 adjudication — Zitrone PR #46 (head 71ec27b)

Adjudication of two independent, blind adversarial reviews:
- Codex: `/root/l00prite/d2c-r13-review-codex.md` (2 findings, both labelled P1)
- Grok: `/root/l00prite/d2c-r13-review-grok.md` (3 findings: P2, P3, P3)

All claims verified against source at `71ec27b`. Rule applied: resolve against the actual
code, never split the difference between reviewers, never defer to seniority.

---

## Summary

- **Total findings filed across both reports:** 5 line-items (Codex 2, Grok 3), mapping to
  **3 distinct defects** + 1 informational (self-healing) + 1 documentation-drift non-defect.
- **Confirmed P1:** 1 — F1 (auth/account cleared before the confirmed marker is durable;
  broken crash roll-forward). *Both reviewers found this independently (Codex P1, Grok P2).*
- **Confirmed P2:** 1 — F2 (`create()` trusts `File.delete()` bool + clears stale markers
  after the successor vault is already durable). *Both found it (Codex P1, Grok P3).*
- **Confirmed P3:** 1 — F3 (`clearDeleteIntent()` ignores its `dirSync` result — hygiene).
  Plus F4 (informational: `destroy()` marker-retire trusts `delete()` bool — self-healing over
  an empty image) and F5 (stale `classifyDeliveryCommit` kdoc — documentation drift, not a
  defect; Grok flagged this correctly).
- **False positives:** 0. Neither reviewer raised the Base64/minSdk or compile-error classes;
  every filed finding verified against source is real.
- **Blocks merge:** **YES.** F1 is a confirmed P1, and no merge over any unresolved finding is
  absolute. F2 (P2) and F3 (P3) also remain open.

### What changes before the next review round
1. Fix F1: stop clearing tokens/account inside `deleteAccount()` on success; make
   `persistServerDeleteConfirmed()` required-durable in the coordinator; make the Splash
   intent-only path **reconcile** (retry the authenticated DELETE) instead of clearing intent.
2. Fix F2: in `create()`, clear + `dirSync` + **re-stat markers absent** *before* writing the
   successor vault; fail create with no vault on disk if a marker can't be confirmed gone.
3. Fix F3: `clearDeleteIntent()` checks its `dirSync` result (fail-closed or documented).
4. Clean F4/F5: apply the re-stat discipline to `destroy()`'s marker retire; update the stale
   `classifyDeliveryCommit` kdoc to the render-first order.
5. Round-14 fixes get **another independent two-reviewer pass** — the round-13 fixes themselves
   introduced F1/F2, exactly the round-12 pattern (moved *when* a signal is written without
   re-deriving what every reader assumes it *means*).

---

## Cross-reviewer map

| Distinct defect | Codex | Grok | My verdict |
| --- | --- | --- | --- |
| F1 — auth cleared before durable confirmed marker; broken roll-forward | P1 (C1) | P2 (P2-1) | **CONFIRMED P1** |
| F2 — `create()` trusts `delete()` bool + clears markers after successor vault durable | P1 (C2) | P3 (P3-1) | **CONFIRMED P2** |
| F3 — `clearDeleteIntent()` ignores `dirSync` result | alluded ("same discipline") | P3 (P3-2) | **CONFIRMED P3 (hygiene)** |
| F4 — `destroy()` marker retire trusts `delete()` bool | alluded (C2) | — | **Informational (self-healing over empty image)** |
| F5 — `classifyDeliveryCommit` kdoc "caller MUST still render" | — | non-finding (drift) | **Doc drift, not a defect (Grok correct)** |

**Corroboration:** the two reviewers, blind to each other, independently found F1 and F2 — the
same two durability-ordering gaps. That convergence is strong evidence both are real, and it
again shows a single reviewer is insufficient: Codex rated both as P1; Grok rated them P2/P3;
the source-grounded truth is P1 + P2.

---

## F1 — CONFIRMED P1 — auth/account cleared before the confirmed marker is durable

**Locations (verified):**
- `ApiClient.kt:371-375` — on `CONFIRMED_GONE`, `clearTokens()` + `authStore.clearAccount()` +
  `_accountId.value = null` run *inside* `deleteAccount()`, at classification time.
- `MessagingCoordinator.kt:1394-1396` — `runCatching { persistServerDeleteConfirmed() }` is
  **best-effort / swallowed**; execution continues to teardown regardless.
- `MainActivity.kt:1098-1110` — Splash intent-only branch: `serverDeleteConfirmed() == false`
  → asynchronously **clears** the intent marker and routes to normal unlock (never
  DeleteIncomplete, never a reconcile).
- Supporting: `ApiClient.request()` attaches `Authorization` only when `accessToken != null`
  (verified `net/ApiClient.kt`); boot loop registers a new account when `api.accountId == null`
  (`MessagingCoordinator.kt:364-365`).

**Verified sequence:**
```
1. persistDeleteIntent()            durable  → intent marker present
2. api.deleteAccount() == CONFIRMED_GONE
     → clearTokens(); authStore.clearAccount(); _accountId = null   // AUTH GONE (may reseal)
3. runCatching { persistServerDeleteConfirmed() }                   // BEST-EFFORT (swallowed)
4. teardown; onConfirmed() → destroy()  (destroy also writes the confirmed marker, required-durable)
```

**Crash window (in threat model — "crash/kill mid-flow"):** a crash after step 2 and before a
*durable* confirmed marker (step 3 can be swallowed on failure; a crash can land before step 4's
destroy fsyncs it). On restart:
- `vault.delete-confirmed`: absent → `serverDeleteConfirmed() == false`.
- `vault.delete-intent`: present → Splash **clears it** and routes to normal unlock.
- Auth was already cleared at step 2. If `authStore.clearAccount()`'s coalesced reseal landed,
  the reopened vault has `accountId == null` → the boot loop **registers a brand-new account**
  under the user's existing identity key. If `accountId` survived, `createSession` (identity-
  signed) hits the now-deleted account → 401/404 → forced-logout/stuck. A manual re-DELETE goes
  out **token-less** (`accessToken == null`) → 401 → classified `DEFINITE_FAILURE` → no destroy,
  intent abandoned.

**Harm:** the server account is gone, but the local vault (identity/roster/ratchet) is retained
after the user requested deletion — a retention-after-requested-delete gap in a plausible-
deniability product with a *published* permanence guarantee. The recovery the two-marker design
promised (intent → retry → 404 → confirm → destroy) does not exist: Splash discards intent, and
the auth needed to reach the idempotent 404 was cleared first. Worst reachable outcome: silent
re-registration of a new server account bound to the old identity key.

**Severity resolution (Codex P1 vs Grok P2):** P1. Grok's P2 is defensible on reachability
(needs a crash in a ~ms window), but understates two things the source confirms: (a) the design's
own crash-safe roll-forward is *defeated*, not merely narrow — the same structural failure as the
round-12 regression; and (b) the reachable silent-re-registration path binds a fresh account to
the old identity. Retention-after-delete + broken recovery + possible re-registration is P1 for
this threat model.

**Structural note (the round-12 pattern, again):** the round-13 design added the intent marker to
*enable* crash recovery, but the reader that consumes it (Splash intent-only) assumes intent-only
means "abandoned," while a writer can leave intent-only meaning "server deleted, crashed before
confirmed durable." A reader assuming a meaning that does not hold for every writer state — the
exact class we committed to catch with the invariant table. The table below makes it explicit.

### WRITER/READER table — F1

| Writer | State the write *implies* | Durable-before-next-reader? |
| --- | --- | --- |
| `persistDeleteIntent()` (step 1) | "delete initiated; server outcome unknown" | Yes (required-durable) |
| `deleteAccount()` auth clear (step 2, `ApiClient.kt:371`) | "server confirmed gone" | **Runs before the confirmed marker is durable** |
| `persistServerDeleteConfirmed()` (step 3) | "server gone; destroy owed" | **No — best-effort/swallowed** |
| `destroy()` confirmed write (step 4) | "server gone; destroy owed" | Yes (required-durable) — but only reached if no crash first |

| Reader | Assumption | Holds for every writer state? |
| --- | --- | --- |
| Splash `serverDeleteConfirmed()==false` → clear intent + normal unlock (`MainActivity.kt:1098`) | "intent-only = abandoned delete" | **NO** — fails when intent-only = "server gone, crashed before confirmed durable" |
| Boot loop `accountId == null` → register (`MessagingCoordinator.kt:364`) | "no account = fresh install" | **NO** — fails when accountId was cleared by a crashed delete → re-registration |
| Recovery re-DELETE (`request()` auth-attach) | "authenticated, will reach 404" | **NO** — token cleared → 401 → DEFINITE_FAILURE → abandon |

**Fix (proposal — not implemented):**
1. `deleteAccount()`: do **not** clear tokens/account on success. Return the classification only.
2. Coordinator, on `CONFIRMED_GONE`: call `persistServerDeleteConfirmed()` **required-durable**
   (not `runCatching`); only *after* it confirms durable, clear tokens/account, then teardown +
   `destroy()`. If the confirmed marker cannot be made durable, route DeleteIncomplete / retry —
   never fall through to a state where auth is gone but no durable authorization exists.
3. Splash intent-only path: **reconcile, not clear** — with retained credentials, re-attempt the
   authenticated DELETE; 404/2xx → CONFIRMED_GONE → write confirmed → destroy; a genuine
   DEFINITE_FAILURE (account truly still present) may then clear intent / offer normal unlock.
   (This revisits the round-13 "clear intent on boot" decision, which is the proximate cause.)

**Re-verification must specifically check:** for the confirmed marker and the auth-state, re-derive
what *every* reader assumes — Splash (intent-only), the boot loop (`accountId == null`), and the
recovery DELETE (`accessToken == null`). Prove that after any single crash point in
`deleteAccountAndWipe`, the surviving on-disk state routes to either a completed destroy or a
recoverable roll-forward — never to re-registration or a silently retained vault.

---

## F2 — CONFIRMED P2 — `create()` trusts `File.delete()` bool and clears stale markers late

**Location (verified):** `VaultImageStore.kt:446-452` (create); same trust-the-bool shape at the
`destroy()` retire `:731-735` (F4) and `clearDeleteIntent()` `:659-663` (F3).

**Verified code:**
```kotlin
val hadStaleMarker = deleteIntentFile.exists() || serverDeletedFile.exists()
if (hadStaleMarker) {
    deleteIntentFile.delete()      // bool ignored
    serverDeletedFile.delete()     // bool ignored — File.delete() returns false on I/O failure
    if (dirSync(baseDir) != DirSyncResult.DURABLE) throw VaultImageException.NotDurable()
}
```
This runs **after** `vault.bin`/`vault.dek` are already renamed into place and dir-fsynced (the
successor vault is durable) and is **not** re-statted.

**Two failure shapes (both verified reachable in principle):**
1. **Silent unlink failure:** `serverDeletedFile.delete()` returns false (I/O/filesystem fault)
   but the marker file remains; `dirSync` still returns `DURABLE`; `create()` returns success with
   a live vault **and** `serverDeleteConfirmed() == true`. Next Splash → DeleteIncomplete → auto
   `destroy()` → **the valid successor vault is destroyed.**
2. **Clear-after-write ordering:** the markers are cleared after the vault is durable, so a crash
   in the window "vault durable, marker-clear not yet durable" leaves the new vault + a stale
   confirmed marker → same successor auto-destroy on next boot.

**Severity resolution (Codex P1 vs Grok P3):** P2. The consequence is catastrophic (data loss of a
re-onboarded identity), so Grok's P3 ("hygiene") understates it. But it is *compound-narrow*: it
needs a resurrected stale marker to already exist (itself the rare journal-replay event F1/P1-2
concerns) **and** an unlink I/O failure **and** a `DURABLE` fsync — so Codex's P1 overstates the
standalone reachability. Independently assessed (not a split): catastrophic consequence + compound
preconditions = P2. It is also inconsistent with the discipline `destroy()` already uses for the
*vault files* (re-stat via `exists()` rather than trusting `delete()`); the round-13 fix applied
that discipline to the vault files but not to the markers in `create()`.

### WRITER/READER table — F2

| Writer | State the write *implies* | Actually guaranteed? |
| --- | --- | --- |
| `create()` marker `delete()` (bool ignored) + `dirSync` (`VaultImageStore.kt:449`) | "stale markers cleared" | **No** — `File.delete()==false` on I/O failure leaves the marker; only the fsync is checked |
| `create()` marker clear ordering (after vault durable) | "no stale marker coexists with the new vault" | **No** — a crash between vault-durable and clear-durable leaves both |

| Reader | Assumption | Holds? |
| --- | --- | --- |
| Splash `serverDeleteConfirmed()` over the new image (`MainActivity.kt:1098`) | "confirmed present = server gone, destroy owed" | **NO** — the marker is stale from a prior account; the new vault is valid |
| `onRetryDestroy` → `destroy()` (`MainActivity.kt:632`, `1120`) | "safe to destroy whatever image is present" | **NO** — destroys the successor vault |

**Fix (proposal):** clear the markers **before** writing the successor vault: `delete()` + `dirSync`
+ **re-stat** `!serverDeletedFile.exists() && !deleteIntentFile.exists()`; if either survives or the
fsync is not `DURABLE`, throw with **no** successor vault on disk (fully retryable). Do not trust
the `delete()` bool anywhere a marker's absence is load-bearing.

**Re-verification must specifically check:** after the create-time marker clear, re-derive what
Splash/`onRetryDestroy` assume `serverDeleteConfirmed()` means over a *freshly created* vault —
prove the marker is verified absent (re-statted), not merely fsynced, before create returns.

---

## F3 — CONFIRMED P3 (hygiene) — `clearDeleteIntent()` ignores `dirSync`

**Location (verified):** `VaultImageStore.kt:659-663` — `deleteIntentFile.delete(); dirSync(baseDir)`
with the `dirSync` result discarded. A non-durable clear can leave the intent marker on disk.

**Impact:** none security-relevant. Intent alone never authorises destruction (F1 aside), and
Splash re-clears intent-only on the next boot. Hygiene only — both reviewers agree it is low
(Grok P3-2; Codex "apply the same discipline"). *Note:* once F1 is fixed by making intent
load-bearing for reconcile, this becomes more important — a non-durable intent clear could drop a
recovery signal — so fix it together with F1.

**Fix:** check the `dirSync` result (fail-closed or documented no-op), consistent with the other
marker primitives.

---

## F4 — Informational — `destroy()` marker retire trusts `delete()` bool

**Location:** `VaultImageStore.kt:731-735`. Same pattern as F2 (bool ignored, only fsync checked),
but here it is **self-healing**: a surviving marker sits over an *empty* image (files verified
gone), so the next boot routes DeleteIncomplete → `destroy()` re-runs idempotently, re-stats the
files absent, and retries the marker unlink. No successor vault is at risk. Worth tightening for
consistency (re-stat), but not a standalone defect. Codex correctly noted the pattern; the impact
differs from `create()` because there is no live successor image.

---

## F5 — Documentation drift (not a defect) — `classifyDeliveryCommit` kdoc

**Location:** `LemonDropRedeemer.kt:270-280` — the kdoc still says "so the caller MUST still
render," which was the commit-*before*-render contract. Round 13 moved to render-*gated* consume
(the caller renders first, then calls `deliverDurablyCommit`/`classifyDeliveryCommit`), so the
sentence is stale. Grok flagged this correctly as documentation drift only; the call site
(`MainActivity.openLemonDrop`) is render-first and the runtime behavior is correct. Clean up the
kdoc; no code change.

---

## Focus areas both reviewers marked clean — spot-verified, concur

- **Two-marker auth semantics:** auto-destroy (`DeleteIncomplete`) is gated on
  `serverDeleteConfirmed()` only, at Splash, the reconciler, and `onRetryDestroy`. Intent alone is
  never destroy authorization. Clean (apart from F1's recovery gap).
- **`CONFIRMED_GONE`/`DEFINITE_FAILURE`/`AMBIGUOUS` classification:** 2xx/404 → gone; other 4xx →
  definite failure; 5xx/`IOException` → ambiguous; ordering `ApiException` before `IOException` is
  correct (`ApiException extends IOException`). Clean.
- **Non-confirmed branching:** neither `DEFINITE_FAILURE` nor `AMBIGUOUS` runs teardown/destroy;
  session stays live; `onNotConfirmed` lifts the gate. Clean.
- **Durable-gated peer burn (P1-B):** `ws.burnMessage` fires only on `DURABLE` immediately or after
  the deferred flush confirms; withheld on exhausted/cancelled `APPLIED_UNCONFIRMED`. Clean.
- **Render-gated lemon-drop consume (P2-1):** render decided on Main (`activityStarted` + CAS to
  this drop's own `AwaitUnlock`) before any consume; backgrounded/stolen → nothing consumed. Clean.
- **No plaintext behind a stopped Activity:** Main-serialized `activityStarted` + CAS vs
  `onStop`'s Delivered-clear. Clean.

---

## Merge decision

**Do not merge.** F1 is a confirmed P1; F2 (P2) and F3 (P3) are also open. No merge over any
unresolved finding. Round-14 fixes must pass another independent two-reviewer adversarial pass
before merge — the round-13 fixes introduced F1/F2, which is the round-12 pattern repeating, so a
self-re-read is explicitly insufficient here.
