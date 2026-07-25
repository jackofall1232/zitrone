# Round-14 adjudication — Zitrone PR #46 (head 5a136e9)

Adjudication of two independent, blind round-14 reviews:
- Codex: `/root/l00prite/d2c-r14-review-codex.md` (1 P1, 1 P2)
- Grok: `/root/l00prite/d2c-r14-review-grok.md` (0 P1, 1 P2 — "revoke-race")

All claims verified against source at `5a136e9`. Rule: resolve against the code, never split the
difference, never defer to seniority.

---

## Summary

- **Total distinct findings:** 2 (both CONFIRMED real). Zero false positives.
  1. **Revoke-race** — `onSessionRevoked()` clears tokens in the delete window. *Both* reviewers
     found it (Codex **P1**, Grok **P2**).
  2. **`File.exists()` indeterminate-stat** — the marker re-stat conflates absent with
     stat-failure. Codex **P2**; Grok rated the same helpers clean.
- **Confirmed severities (adjudicated):**
  - Finding 1: **CONFIRMED P2** (Grok is right; Codex overstates — see below).
  - Finding 2: **CONFIRMED P2**, narrow end (Codex is right that it's real; Grok missed it).
- **False positives:** 0.
- **Blocks merge:** **YES.** Two confirmed findings; no merge over any unresolved finding is
  absolute — independent of the P1/P2 outcome.
- **The severe round-12/13 classes are all CLOSED** (verified by both reviewers): no auto-destroy
  over a live account, no silent re-registration, no permanent message loss, no marker-conflation
  auto-destroy. What remains are two narrow crash/filesystem-boundary defects.

### Risk of shipping as-is (direct answer)
Bounded and mostly fail-safe, but real:
- **Finding 1 fails SAFE.** If a server revocation (which the relay commonly sends right after it
  purges an account) lands in the ~millisecond window between the server confirming the delete and
  `vault.delete-confirmed` becoming durable, and the process then dies, the device **retains the
  (passphrase-encrypted) vault after the user asked to delete their account**, and the app can't
  auto-finish clearing it (the reconcile can't re-authenticate a gone account with the tokens
  wiped). No data loss, no data leak, no silent re-registration, no unauthorized destruction — but
  for a plausible-deniability product whose published guarantee is "deletion is permanent /
  everything gone," a device holding the vault after a completed server-side delete is a real
  completeness gap. Recovery requires clearing app data / reinstalling.
- **Finding 2 does NOT fail safe** but is extraordinarily narrow: only on a genuinely failing
  filesystem where a stale `delete-confirmed` marker survives an unlink, a stat misreports it
  absent, and the dir-fsync still succeeds, can `create()` write a new vault beside the stale
  marker — and a later boot then auto-destroys that valid re-onboarded vault (data loss). The
  trigger is a compound of rare I/O misbehaviors that effectively do not occur on healthy Android
  internal storage.
- **Neither is adversary-exploitable** (no path to extract data or bypass the passphrase gate).

Net: shipping as-is means accepting two rare crash/FS-boundary gaps — one fail-safe retention, one
narrow data-loss — while the catastrophic classes are closed. Both have small, clean fixes, and the
standing rule blocks merge over either. Recommendation: fix both (they're proportionate), then the
next independent pass.

### What changes before merge
1. Finding 1: prevent any token clear during a delete's intent→confirmed interval (a
   `@Volatile deleteInFlight` guard that `onSessionRevoked` honors), so auth survives until the
   confirmed marker is durable — globally, not only inside `deleteAccount()`.
2. Finding 2: make the marker re-stat a true tristate — treat an indeterminate stat as
   NOT-absent (fail closed) via `java.nio.file.Files.notExists(...)` (already imported), in
   `clearBothMarkersDurably()` and `clearDeleteIntent()`.
3. Housekeeping: the `deleteIntentPending()` kdoc still mentions "clearing intent on boot" — drift
   (Grok noted); fix while in the file.

---

## Cross-reviewer map

| Finding | Codex | Grok | Adjudicated |
| --- | --- | --- | --- |
| 1 — `onSessionRevoked` clears tokens in the delete window | P1 | P2 | **CONFIRMED P2** |
| 2 — `File.exists()` re-stat conflates absent / indeterminate | P2 | clean (missed) | **CONFIRMED P2 (narrow)** |

Both reviewers independently found Finding 1 — strong corroboration of the defect (they disagree
only on severity). On Finding 2, the round-13 pattern repeats: Codex caught a real gap Grok's
"re-stat propagates failure" table missed. Neither pass alone was complete.

---

## Finding 1 — CONFIRMED P2 — `onSessionRevoked` clears tokens between `CONFIRMED_GONE` and durable confirmed

**Locations (verified):**
- `MessagingCoordinator.onSessionRevoked()` (`:1796-1820`) — synchronous body runs on the **socket
  callback thread**; `api.clearTokens()` at `:1806`.
- `deleteAccountAndWipe()` (`:1363-1427`) runs on `scope.launch(confined + NonCancellable)`; the
  vulnerable window is after `api.deleteAccount()` returns `CONFIRMED_GONE` (`:1383`) and before
  `persistServerDeleteConfirmed()` returns durable (`:1405-1415`). The session is still live
  (ws not disconnected until `:1419-1422`).
- `ApiClient.clearTokens()` → `authStore.clearTokens()` clears **only** access/refresh tokens
  (`AuthStore.kt:107-109` and `:154-156`); it does **not** touch `accountId` (`clearAccount()` is a
  separate method, `:111-113` / `:158-160`), and `onSessionRevoked` does not call it.

**Mechanism (verified reachable):** an authenticated DELETE succeeds (server account gone →
`CONFIRMED_GONE`); the relay tears the session down and sends a revoke (common right after a purge);
`onSessionRevoked` — outside the delete coroutine's serialization — clears the vault-backed tokens
(and `onForcedLogout` → `lockIf` → `runtime.close()` can durably reseal that cleared-token state);
the process dies before `vault.delete-confirmed` is durable. On restart: intent present, confirmed
absent → Splash → Locked → reconcile. But the tokens are gone and the account is gone, so
`createSession` can't re-auth and the reconcile DELETE goes token-less → 401 → `DEFINITE_FAILURE` →
intent kept, no completion. The vault is retained indefinitely.

**Severity resolution (Codex P1 vs Grok P2): P2. Grok is right.**
- Verified: `clearTokens()` does **not** clear `accountId`, so the round-13 F1 worst case — silent
  re-registration of a new account under the old identity — **cannot occur** here (the boot loop's
  `accountId == null → register` is not triggered). Codex's write-up worries about the general
  "unreconcilable state" but does not establish re-registration or any data destruction.
- The outcome is **retention + a stuck reconcile**: data is not lost, not leaked, not
  re-registered, and never auto-destroyed. That is a conservative fail-safe.
- By the stated rubric (P1 = exploitable / data-integrity-breaking; P2 = conservative-failure-mode
  gap), a fail-safe retention with no exploit and no loss is **P2**. Codex's P1 conflates "a real,
  fixable code defect" (true — the app clears usable tokens mid-delete, defeating round-14's global
  auth-survival invariant) with "data-integrity-breaking / exploitable" (false — it fails safe).
- It is **not** merely the honest natural-expiry residual: it is a concrete code path that
  *causes* the unreconcilable state, and it is fixable — so it is a legitimate P2 finding, not an
  informational residual.

### WRITER/READER table — vault-backed AUTH tokens (the signal Finding 1 implicates)

| Writer | Implies | Ordered w.r.t. the confirmed marker? |
| --- | --- | --- |
| `deleteAccountAndWipe` (round 14) | leaves auth intact through the whole flow; auth dies only with `destroy()` | Yes — by design, auth survives until confirmed+destroy |
| `onSessionRevoked` → `api.clearTokens()` (`:1806`) | "session invalid → drop tokens" | **NO — runs on the socket thread, unordered w.r.t. the delete coroutine; can clear tokens inside the intent→confirmed window** |

| Reader | Assumption | Holds for every writer state? |
| --- | --- | --- |
| Post-unlock reconcile DELETE (`MainActivity.kt:1027`) | "auth is available until the deletion is durably confirmed" | **NO** — the revoke writer can strip tokens before confirmed is durable → 401 → stuck |
| Boot loop `accountId == null → register` | "fresh install" | Holds — `accountId` is not cleared by revoke → no re-registration |

The round-14 invariant "auth survives until `vault.delete-confirmed` is durable" was implemented
only *inside* `deleteAccount()`; the table shows a second, concurrent writer (`onSessionRevoked`)
that violates the same invariant. Same structural shape as before — a global invariant enforced at
one site but not at every writer.

**Fix (proposal):** a `@Volatile` "delete in flight" flag set when the intent marker is durable and
cleared at every terminal exit of `deleteAccountAndWipe` (`onConfirmed` / `onNotConfirmed` /
`onConfirmedNotDurable` / `onIntentNotDurable`). `onSessionRevoked` skips the `clearTokens()` (and
must not let `onForcedLogout`→`runtime.close()` reseal a cleared-token state) while the flag is set —
the delete flow owns teardown, and `destroy()` will remove auth with the vault. Serializing the
clear onto `confined` alone is insufficient: `api.deleteAccount()` is a suspend point, so a
confined-queued clear can still interleave there.

**Re-verify must check:** enumerate *every* caller of `clearTokens`/`clearAccount`/token-mutating
paths (not just `deleteAccount`) and prove none can run in the intent→confirmed interval — the
exact "one writer at one site" miss that produced this finding.

---

## Finding 2 — CONFIRMED P2 (narrow) — `File.exists()` re-stat conflates absent with indeterminate

**Locations (verified):** `VaultImageStore.clearBothMarkersDurably()` (`:679-684`), used by
`create()` (`:404-407`) and `destroy()` (`:752-754`); same pattern in `clearDeleteIntent()`
(`:662-669`).

**Verified code:**
```kotlin
private fun clearBothMarkersDurably(): Boolean {
    deleteIntentFile.delete()
    serverDeletedFile.delete()
    val durable = dirSync(baseDir) == DirSyncResult.DURABLE
    return durable && !deleteIntentFile.exists() && !serverDeletedFile.exists()
}
```
`java.io.File.exists()` returns `false` both when the path is absent **and** when the status cannot
be determined (I/O / permission failure) — it never signals "indeterminate." So the re-stat can
report a marker "absent" when in fact the stat failed and the marker is still present.

**Destructive path (verified logic):** a stale `vault.delete-confirmed` exists; `create()`'s
`delete()` fails silently (marker survives) but the dir-fsync succeeds and `exists()` returns
`false` because the stat couldn't complete → `clearBothMarkersDurably()` returns `true` → `create()`
writes the successor vault → when the FS recovers, the confirmed marker is present beside the new
vault → Splash → `DeleteIncomplete` → auto-destroy of the valid successor.

**Codex is right; Grok's "clean" is incomplete.** Grok's table asserts the helper "returns false
unless … both re-stat absent," treating `exists()==false` as reliable absence — which is exactly the
conflation Codex identified. This is not a false positive.

**Severity: P2, narrow end.** The consequence is destructive (successor auto-destroy → loss of a
re-onboarded identity), so it is *not* fail-safe — which keeps it out of P3. But reachability is a
compound of rare conditions (a pre-existing resurrected stale marker + a silent `delete()` failure +
a stat that returns false-despite-present + a successful dir-fsync, all on internal storage), so it
is not P1. My round-13 F2 test (`create_failsWithNothingWritten_whenAStaleMarkerSurvivesTheClear`)
models the *deletable-but-present* case (a non-empty dir, where `exists()==true`) and passes — it
does not cover the *stat-failure-returns-false* case Codex raises; that gap is real.

### WRITER/READER table — the marker re-stat

| Writer | Implies | Guaranteed? |
| --- | --- | --- |
| `clearBothMarkersDurably` re-stat `!exists()` | "marker confirmed ABSENT" | **No** — `exists()==false` also means "stat indeterminate," so absence is not actually proven |

| Reader | Assumption | Holds? |
| --- | --- | --- |
| `create()` → writes successor vault on `true` | "no stale marker coexists with the new vault" | **No** — an indeterminate stat lets a surviving marker coexist |
| Splash `serverDeleteConfirmed()` over the successor | "confirmed = server gone" | **No** — the marker is a stale survivor over a valid vault |

**Fix (proposal):** replace `!file.exists()` with a true tristate — `java.nio.file.Files.notExists(
file.toPath())`, which returns `true` **only** when the file is confirmed absent (present *or*
indeterminate → `false`). Then `clearBothMarkersDurably()` returns `true` only when `dirSync ==
DURABLE && Files.notExists(intent) && Files.notExists(confirmed)`; apply the same to
`clearDeleteIntent()`'s re-stat. `java.nio.file.Files` is already imported (`VaultImageStore.kt:15`).
Add a host test that injects a stat-indeterminate marker and asserts create/clear fail closed.

**Re-verify must check:** every place a marker's *absence* is load-bearing uses a tristate (confirmed
absent), not `File.exists()==false`; and the fail-closed path aborts with nothing written.

---

## Confirmed CLEAN (both reviewers concur; spot-verified)

- **No auth clear inside `ApiClient.deleteAccount()`** — it only classifies (`ApiClient.kt:347-371`).
- **`accountId` never cleared by the delete flow or by revoke** → no silent re-registration.
- **Required-durable confirmed marker** with `onConfirmedNotDurable` (no teardown on failure).
- **Intent never abandoned** on a not-confirmed outcome; retired only by `destroy()`.
- **`create()` clears markers before any vault byte** with a required dir-fsync and abort-on-failure
  (correct apart from the `exists()` tristate gap of Finding 2).
- **Intent-only never auto-destroys; confirmed is the sole auto-destroy authorization.**
- The **"already unusable token" residual** is correctly scoped as fail-safe retention (not a marker
  invariant break, not re-registration).

---

## Merge decision

**Do not merge.** Two confirmed findings (Finding 1 P2, Finding 2 P2). No merge over any unresolved
finding is absolute, independent of severity. Both have small, proportionate fixes. Round-15 fixes
must pass another fresh independent two-reviewer pass — Finding 1 is again a "global invariant
enforced at one site, violated by a second writer," the recurring structural failure, so a
self-re-read is insufficient.
