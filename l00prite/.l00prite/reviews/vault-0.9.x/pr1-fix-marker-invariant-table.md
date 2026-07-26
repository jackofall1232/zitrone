# PR-1 fix — delete-marker WRITER/READER invariant table (NEW fail-closed add-path semantics)

Built BEFORE writing the fix, per standing discipline (this defect class has now appeared in rounds 12,
15, and PR-1 review). The B1 fix is: **`attemptUnlockOrAdd` never writes or clears a delete marker.** It
becomes a pure fail-closed READER — if it cannot PROVE both markers absent it returns `Rejected` and
creates nothing. This REMOVES it from the marker-writer set entirely, so the rounds-13–16 state machine is
preserved unchanged and no reader's assumption can be falsified by the add path.

## Decision record (B1) — why fail-closed, and why OQ3 was a decision defect (user, 2026-07-24)

`create()` does not clear markers because clearing is inherently safe — it clears because
`require(!binFile.exists())` has already PROVEN the markers are orphaned. **That precondition is the
proof.** `attemptUnlockOrAdd` has no equivalent proof: with a live image, a confirmed marker may be stale
(a `destroy()` that completed the server side but not cleanup) or live (authorization for a destroy that
hasn't happened), and NOTHING observable at the lock screen distinguishes them. Any guarded-clear design
would be a heuristic dressed as an invariant. Fail-closed avoids ever taking an action that depends on a
distinction the code cannot make. OQ3's "clear like create()" was a decision error (mine): it copied
create()'s ACTION without create()'s PROOF.

## WRITERS of `vault.delete-intent` / `vault.delete-confirmed` (AFTER the fix)

| Writer | Effect | Precondition / proof | Changed by PR-1? |
|---|---|---|---|
| `markDeleteIntent()` | + intent (durable) | delete initiated | no |
| `markServerDeleteConfirmed()` | + confirmed (durable) | server returned definite-gone (2xx/404) | no |
| `destroy()` | + confirmed, unlink image, then clear both | account-delete completion | no |
| `clearDeleteIntent()` | − intent (durable, re-stat) | explicit intent retire | no |
| `clearBothMarkersDurably()` | − both (durable, re-stat) | callee of create()/destroy() ONLY | no |
| `create()` | clears both, then writes fresh image | **`require(!binFile.exists())`** — image ABSENT ⇒ markers provably orphaned | no |
| `retireLegacyImage()` | — (never touches markers) | — | no (added, but marker-neutral) |
| **`attemptUnlockOrAdd()`** | **— (never writes/clears a marker)** | **n/a — it is now a READER only** | **YES: removed from writer set** |

⇒ The writer set is EXACTLY the pre-PR-1 rounds-13–16 hardened set. No new writer. No writer clears a
marker over a live image.

## READERS + assumptions, proven against every writer state

| Reader | Predicate | Assumption | Holds after fix? |
|---|---|---|---|
| `serverDeleteConfirmed()` | confirmed present | server account provably gone → authorize `Route.DeleteIncomplete` auto-destroy | YES — only marker/destroy write confirmed; the add path never clears it, so a pending confirmed survives a B-create-attempt |
| `deleteIntentPending()` | intent ∧ ¬confirmed | delete initiated, unconfirmed → reconcile (retry DELETE) on next session | YES — the add path never clears intent, so a pending reconcile survives |
| `hasDeleteIntentMarker()` | ¬`Files.notExists(intent)` (present-or-indeterminate) | auth-protection lifetime: do not strip vault-backed tokens | YES — add path never clears intent |
| Boot Route (MainActivity) | confirmed→DeleteIncomplete; else image→Locked | confirmed cannot be silently erased under a normal lock/create UI | YES — add path never erases it |
| MessagingCoordinator auth/teardown guards | intent/confirmed continuity | markers persist until a legitimate delete-state transition | YES — add path is marker-neutral |
| **NEW: `attemptUnlockOrAdd` create-gate** | **`Files.notExists(intent) ∧ Files.notExists(confirmed)`** | **"if I cannot PROVE both absent, a delete may be in flight → do NOT create, do NOT clear, return Rejected."** Makes NO assumption about WHICH marker or stale-vs-live — treats any-present-or-indeterminate as "don't create." | YES — needs no distinction the code cannot make |

**Central proof:** NO reader's assumption depends on the add path having cleared anything. Before the fix,
the add path's clear FALSIFIED `serverDeleteConfirmed` / `deleteIntentPending` (they read "absent" while a
delete was truly pending). After the fix, the add path clears nothing, so every reader observes the TRUE
marker state at all times — identical to rounds 13–16.

## Crash / TOCTOU states (the new writer's absence makes this trivial)

- The create-gate's `Files.notExists` check and the image write are in the SAME `imageLock` critical
  section as the sweep. `markDeleteIntent` / `markServerDeleteConfirmed` also take `imageLock`, so NO
  marker write can interleave between the gate check and the bin write (TOCTOU closed — B1 requirement 3).
- On the marker-present branch there is NO write at all → no crash-between-two-writes state exists.
- On the marker-absent (create) branch, the only write is `vault.bin` (never a marker), so a crash at any
  point leaves the markers exactly as they were (absent) — consistent with every reader.

## Parity note (folds in F6)

Both the marker-present reject and the ordinary reject perform the SAME crypto: 5 Argon2id + the
self-verifying candidate seal + exactly one 256 KiB payload GCM (throwaway), and NO write. So a
create-attempt that fails closed on a pending delete is indistinguishable (crypto + no-write) from an
ordinary wrong password — no error path skips the payload GCM (F6 resolved), no throw (no side channel
when the device is mid-delete). Disclosed in SECURITY_MODEL.md.
