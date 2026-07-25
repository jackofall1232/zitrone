# Round-15 adjudication — Zitrone PR #46 (head 1adcd00)

Adjudication of two independent round-15 reviews:
- Codex: `/root/l00prite/d2c-r15-review-codex.md` (1 P2)
- Grok: `/root/l00prite/d2c-r15-review-grok.md` (0 P1/P2; 3 informational residuals)

All claims verified against source at `1adcd00`. Rule: resolve against the code; a reviewer's
severity label is not automatically correct; never split the difference.

---

## Summary

- **Total findings:** Codex 1 (P2); Grok 0 P1/P2 + 3 informational (deleteSession dead code;
  routing `exists()` bias; natural token expiry). The two reviewers **disagree head-to-head** on
  the same code (the `deleteInFlight` guard): Codex flags it P2, Grok's lifecycle analysis
  (its §2) rates it CLEAN.
- **Is Codex's P2 the same as one of Grok's informational items?** **No — it is neither.** It is
  not the dead-`deleteSession` item (different code path), not the routing-`exists()` item
  (different code path), and it is *not* Grok's natural-token-expiry residual (Grok explicitly
  scoped that to "**without** a concurrent clear"; Codex's finding *is* the concurrent clear). It
  is a real gap in the round-15 guard that Grok missed by scoping "R14-1 fixed" to the
  in-coroutine window only.
- **Adjudicated severity: CONFIRMED P2** (Codex is right; Grok's "clean" is incomplete). The
  consequence is the same fail-safe retention / stuck-reconcile class adjudicated P2 in round 14.
- **R14-2 marker tristate:** both reviewers verify **CLEAN / fully closed**. Concur.
- **False positives:** 0.
- **Blocks merge:** **YES.** One CONFIRMED P2 remains; no merge over any unresolved finding is
  absolute. **The PR is not clear to merge.**
- Grok's three residuals, in isolation, would be genuinely informational (dead code / intentional
  safe bias / pre-existing product residual) and would **not** independently block merge — but
  they are not the whole picture, because Codex's P2 is a separate, live, confirmed defect.

### What changes before merge
1. Make the auth-survival guard track the **durable** delete state, not the coroutine lifetime:
   `onSessionRevoked` must skip clearing tokens whenever a delete is pending per the intent
   marker (`deleteIntentPending()` on disk) — not only while `deleteInFlight` is set. Maintain a
   `@Volatile` mirror re-derived from the intent marker at construction, set when intent becomes
   durable, and cleared only on a confirmed destroy/abandon (not in the coroutine `finally`).
2. Housekeeping (optional): Grok's dead-`deleteSession` sink could grow a guard for
   defense-in-depth if it is ever wired; not required at this head.
3. Round-16 fixes need a fresh independent two-reviewer pass — this is the third consecutive round
   where a "guard" fix left a residual of the same class, so a self-re-read is insufficient.

---

## The finding

### CONFIRMED P2 — `deleteInFlight` guards the coroutine's lifetime, not the durable intent state

**Locations (verified):** `MessagingCoordinator.deleteAccountAndWipe()` (`deleteInFlight = true` at
the coroutine start; cleared in `finally` on every exit) and `onSessionRevoked()` (`if
(deleteInFlight) return` before `api.clearTokens()`).

**Codex's core claim, verified against source:** the guard's lifetime is shorter than the durable
state it is meant to protect. `deleteAccountAndWipe` **keeps** `vault.delete-intent` on the
`DEFINITE_FAILURE`, `AMBIGUOUS`, and confirmed-not-durable exits (so a later unlock can reconcile),
but the `finally` clears `deleteInFlight = false` on all of them. A subsequent `session.revoked`
then finds `deleteInFlight == false` and clears the tokens the future reconcile still needs.

**Reachable scenario (verified end-to-end in source):**
1. `deleteAccountAndWipe` → `api.deleteAccount()` returns `AMBIGUOUS` (the server actually deleted
   the account but the response was lost / timed out — a realistic response-loss).
2. `onNotConfirmed(false)` runs: it does `endTerminalWipe()` + sets `lockError` and **nothing
   else** — the session stays live, the socket stays connected (`ws.disconnect()` only runs on the
   `CONFIRMED` step 4), and the intent marker persists. The coroutine's `finally` sets
   `deleteInFlight = false`.
3. The relay revokes the (now server-deleted) account's session; the still-connected socket
   delivers `session.revoked` → `onSessionRevoked` → `deleteInFlight == false` → `api.clearTokens()`
   → `onForcedLogout` → `lockIf` → `runtime.close()` durably reseals the cleared-token state.
4. On the next unlock, the reconcile (`deleteIntentPending()` → `onDeleteAccount`) fires a
   **token-less** DELETE (`request()` attaches `Authorization` only when `accessToken != null`) →
   401 → `DEFINITE_FAILURE` → intent kept, no completion. The vault is retained indefinitely.

**Codex's two secondary mechanisms** (also real, far narrower): (b) after process death the flag
defaults false and is never re-derived from the intent marker, so on restart a revoke can clear
tokens before the reconcile coroutine sets the flag (narrow — the socket must connect and receive a
revoke faster than a local coroutine sets a flag); (c) the `@Volatile` check/clear is non-atomic, so
a revoke that passed `if (deleteInFlight)` can clear tokens after the coroutine sets it true (a
microsecond window). All three share one root cause: **a process-local, coroutine-lifetime mutex
does not represent a durable, restart-spanning phase.**

**Severity resolution — CONFIRMED P2 (Codex right; Grok's "clean" incomplete):**
- Verified real and reachable (scenario above needs no crash — just an AMBIGUOUS delete plus a
  revoke, both realistic when the server processed the delete but the client didn't confirm).
- The consequence is **fail-safe retention + stuck reconcile** — no data loss, no leak, no
  re-registration, no unauthorized destruction (`clearTokens()` clears tokens only, not
  `accountId`, verified in both `AuthStore` impls). So it is not P1.
- It is not informational: it is a live defect with a real completeness/security-adjacent
  implication (retention-after-requested-delete violates the published permanence guarantee in this
  path), not dead code or an unreachable branch.
- It is the **same class** Codex correctly ties to round-14 R14-1 (which was adjudicated P2). The
  round-15 guard narrowed R14-1 (closed the in-coroutine window) but did not close it — the
  post-coroutine and cross-restart windows remain. So R14-1 is **not fully resolved**.

**Why Grok rated it clean, and why that's wrong:** Grok's §2 examined the `deleteInFlight`
lifecycle and concluded "crash-safe for the intended recovery model," arguing the durable recovery
uses "disk markers + vault auth, not the flag." That is right *except* for the unstated precondition
"if [auth is] never cleared" (Grok's own table row). Codex demonstrates auth **can** be cleared
after the coroutine (via `onSessionRevoked`) while the intent persists — the exact path Grok did not
trace. Grok scoped "R14-1 fixed" to the in-coroutine race and placed everything else under either
"R14-1 fixed" or the natural-expiry residual, leaving the post-coroutine concurrent-clear in the
gap. This is the round-13/14 pattern again: each reviewer caught something the other missed; here
Codex caught the gap and Grok's clean verdict is incomplete.

### WRITER/READER table — vault-backed auth vs. the durable delete phase

**The invariant:** auth must survive as long as a delete is *pending* — i.e., from when
`vault.delete-intent` is durable until either a confirmed destroy or an explicit abandon.

| Writer | Implies | Bounded to the invariant's scope? |
| --- | --- | --- |
| `deleteAccountAndWipe` (round 14) | leaves auth intact through the flow | Yes, within the coroutine |
| `deleteInFlight = true` (coroutine start) / `= false` (`finally`) | "a delete owns teardown" | **No — bounded to the COROUTINE, not the durable intent.** Cleared on not-confirmed exits while intent (and the auth need) persist |
| `onSessionRevoked` → `clearTokens()` (guarded on `deleteInFlight`) | "session invalid → drop tokens" | **No — can clear tokens once the coroutine has exited but the intent marker persists** |

| Reader | Assumption | Holds for every writer state? |
| --- | --- | --- |
| Post-unlock reconcile DELETE | "auth is available while a delete is pending (intent durable)" | **No** — a post-coroutine or cross-restart revoke can strip tokens while intent is still pending → 401 → stuck |
| `onSessionRevoked` `if (deleteInFlight)` | "false ⇒ clearing tokens cannot break deletion recovery" | **No** — false also means intent-persists-between-attempts, post-crash intent, or a pre-coroutine window |

The table makes the mismatch explicit: the guard's scope (coroutine lifetime, RAM) is narrower than
the invariant's scope (the durable intent marker's lifetime). The fix must key the guard on the
durable signal.

**Fix (proposal — not implemented):** derive the auth-clear guard from the durable intent marker,
not the coroutine. Maintain a `@Volatile deletePending` that is (i) initialized at coordinator
construction from `imageStore.deleteIntentPending()`, (ii) set true when the intent write becomes
durable, and (iii) cleared only on a confirmed destroy/abandon (NOT in the coroutine `finally`).
`onSessionRevoked` then checks `deleteInFlight || deletePending` before clearing tokens (or bounces
the token clear onto the confined dispatcher behind the delete work to remove the check/clear race).
Avoid a blocking `deleteIntentPending()` file stat on the socket callback thread — use the `@Volatile`
mirror. **Re-verify must check:** every not-confirmed / restart / cross-attempt path where the
intent marker persists keeps auth clearable-only-after the intent is retired; and the flag is
re-derived from the durable marker on construction. This is the exact "guard scope ≠ invariant
scope" miss that produced this finding — prove the guard now spans the intent's whole durable life.

---

## R14-2 (marker tristate) — CONFIRMED CLEAN by both reviewers

Both reviewers independently verified that `create()`'s pre-clear gate, `clearBothMarkersDurably()`,
and `clearDeleteIntent()` now use `Files.notExists()` (true only on a *confirmed* absence;
present-or-indeterminate → false), fail-closed, with no fall-through that treats indeterminate as
absent. The routing readers (`serverDeleteConfirmed`/`deleteIntentPending`) deliberately retain
`File.exists()` because there an indeterminate `false` biases to "not confirmed" → never
auto-destroy — the safe direction. Concur: **R14-2 is fully closed.**

---

## Grok's informational residuals — adjudicated

- **R15-I1 (`deleteSession()` unguarded):** verified **dead code** — no production call site (both
  reviewers agree). Not a live defect; would only matter if wired later. **Informational; does not
  block merge on its own.**
- **R15-I2 (routing `File.exists()` bias):** verified **intentional fail-safe** — indeterminate →
  not-confirmed → no auto-destroy. Distinct from the (fixed) R14-2 absence-proof class.
  **Informational; does not block.**
- **R15-I3 (natural token expiry):** pre-existing product residual, not reintroduced by round 15.
  Note: Grok explicitly excluded the *concurrent-clear* case from this residual — that case is
  precisely Codex's CONFIRMED P2, adjudicated above, not this informational item.

---

## Merge decision

**Do not merge.** One CONFIRMED P2 (the `deleteInFlight` guard does not span the durable intent
state; a post-coroutine / cross-restart revoke can still clear the tokens a reconcile needs). No
merge over any unresolved finding is absolute, regardless of the fail-safe consequence. Grok's three
residuals would not block on their own, but they are not the only finding. Round-16 fixes require a
fresh independent two-reviewer pass — three rounds running, a "guard" fix has left a residual of the
same retention class, so a self-re-read is insufficient.
