# 0.10.3 attachment blob reclaim — implementation review record

Three lenses reviewed the implementation (round 1); two reviewed each correction delta
(rounds 2–3). **Every round found that the previous round's corrections contained new false
claims.** That pattern is the reusable finding, not any individual defect.

| File | Lens | Round | Verdict |
|---|---|---|---|
| `R1-codex-findings.md` | Codex | 1 | Not clean — P1 disclosure (later downgraded 2–1), P2 NonCancellable, P3 test tautology |
| `R1-gemini.md` | Gemini | 1 | Not clean — P2, P3. **Wrong on `NonCancellable` semantics**; disproved by probe |
| `R1-kimi-k3.md` | Kimi K3 | 1 | No P1/P2 destruction defect; four P3. Found the REAL load-bearing invariant |
| `R2-codex.md` | Codex | 2 | Not clean — account-linkage claim false, tripwire false-green, collision wording |
| `R2-grok.md` | Grok | 2 | Not clean — same linkage finding independently, plus two stale kdoc claims |

## What the design got right, and why

No lens could construct an attachment-destruction interleaving. **Kimi located the actual
guarantor**, which neither of the others did: `AttachmentCrypto.encrypt` draws a fresh random
token when no memo is supplied, so a memo re-created after any clearing names a *different*
blob. Hence "memo present + handoff bit absent" implies no envelope naming the current blob was
ever enqueued. Codex had concluded safety rested on the line ordering inside `releaseDeposit`
and called it fragile; that turned out to be a consequence, not the cause.

## Two disputes, both settled by experiment rather than adjudication

- **`NonCancellable`**: Codex said the detached abandon runs after teardown; Gemini said the
  lambda never executes. A ten-line probe settled it — it runs. Codex and Kimi right.
- **Codex's P1 (disclosure on any non-204)**: Kimi independently judged the same state sound.
  Downgraded 2–1 to a kdoc correction. But round 2 then found the *justification* I wrote for
  that downgrade was itself false (the blob store is blind; `DepositBlob` stores no account id),
  so the correction needed correcting.

## Method notes

- Codex burned two entire runs dumping files it had read instead of producing findings, until
  the prompt forbade reading `l00prite/`.
- Kimi K3 completed only via the interactive CLI in plan mode with `/yolo` — `kimi -p` does not
  finish work of this size, as `failures.md` records twice.
