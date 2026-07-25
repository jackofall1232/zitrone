# D3 (PR #48, idle auto-lock) — adjudication

Reviewers: **Grok** (local, `d3-review-grok.md`, this pass) + **Codex** (user-dispatched from PR #48, pending).
Sole safety question: does auto-lock become a NEW writer to `vault.delete-intent` / `vault.delete-confirmed` / token-clearing?

## Grok verdict: NO (matches my line-by-line lock() trace)
Auto-lock is a new *scheduler* for the *existing* `UnlockController.lock()` teardown, not a new writer.
Under (a) rapid bg/fg cycling, (b) TOCTOU vs in-flight delete despite `isTerminalWipe()`, (c) process kill
mid-window — it never touches the two delete markers or any token-clear. Critical/High/Medium: **none**.

## Findings adjudicated (all verified against source; all non-blocking)
| # | Sev | Finding | Verdict | Action |
|---|-----|---------|---------|--------|
| 1 | Low | TOCTOU: `shouldAutoLockAtFireTime()`→`lock()` not atomic w/ `beginTerminalWipe()` | CONFIRMED but **by-design residual**. Both flag ops are `synchronized(lock)`; the non-atomic gap only lets a redundant `lock()` race the delete's *own* teardown, which runs NonCancellable + closed-runtime fail-safe (UnlockController:154,168). Auto-lock contributes `lock()` only — no marker/token write. | NONE (documented; delete side owns safety) |
| 2 | Low | `register()` not idempotent / no unregister | CONFIRMED latent-only. Single call site ZitroneApp.kt:283; process-lifetime observer, correct for AppContainer lifetime. | NONE (optional hardening) |
| 3 | Low | Test gaps: negative-timeout branch (title claims, only `0` asserted); fire-time `(false,true)` 2×2 corner | CONFIRMED. Test-only, zero production risk. Honesty gap (test title vs assertion). | **FIX** — add 2 assertions to AutoLockDecisionTest |
| 4-5 | Info | Durable effect = auth-preserving reseal; settings write device SharedPrefs only | CONFIRMED, intended. | NONE |

## Decision
- No blocking finding. Grok independently corroborates the core safety claim.
- HOLD the test-strengthening fix (#3) to batch with Codex's findings — pushing now would disturb the
  in-flight Codex PR review, and there is no merge/push authorization yet.
- Merge gate unchanged: reconcile Codex + Grok, apply batched fixes, re-verify (413→415 tests), CI green,
  then EXPLICIT user merge approval.
