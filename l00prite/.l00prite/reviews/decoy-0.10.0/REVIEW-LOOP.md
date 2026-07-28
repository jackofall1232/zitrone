# The paired-blind review loop — roster and mechanics (updated 2026-07-28)

## Roster change (maintainer, 2026-07-28)

**Kimi K3 is promoted to a MAIN reviewer.** The pair is now **Kimi K3 + Grok**. **Codex becomes the
tie-breaker**, used on severity disputes and contested judgment rather than on every round.

**Why:** Kimi was only ever given curated dispute prompts, and in that role it repeatedly produced
the decisive reasoning — the "materially modifies *delayed*, not *made less durable*" reading, the
"split pair is a stronger signal than a missing frame" distinction, and the four-step teardown
lifecycle. Too valuable to keep on the sidelines.

**Validated before adopting, not assumed:** its first real file review
(`moon review DecoySendPairing.kt`) **independently found the provisioning CAS/assignment race** that
both main reviewers had found — and analysed it more precisely, naming two failing interleavings and
observing that `CoroutineStart.LAZY` orders assignment before `start()` but does nothing about
`stop()` reading the field before the assignment.

## Mechanics

| Lens | Invocation | Repo access |
|---|---|---|
| **Kimi K3** (main) | `moon review <file>` — purpose-built, per file | reads the file it is given |
| | `moon ask "<prompt>"` — single-shot, self-contained | **none** — sees only what is inlined |
| **Grok** (main) | `grok --sandbox workspace --cwd /root/zitrone -p "$(cat PROMPT.md)"` | full repo, can grep |
| **Codex** (tie-breaker) | `codex exec --sandbox read-only --cd /root/zitrone - < PROMPT.md` | full repo, can grep |
| Gemini (reserve) | `gemini -m gemini-3.1-pro-preview --approval-mode plan -p "..."` | read-only |

Models available to `moon`: `kimi-k3`, `kimi-k2.7-code`, `kimi-k2.7-code-highspeed`, `kimi-k2.6`.

## ⚠️ A methodological caveat that must be stated, not discovered later

**`moon review` reads the file it is handed; `moon ask` reads nothing.** Grok and Codex go looking —
they grep, follow call sites, and check claims against files nobody pointed them at. **Kimi can only
review what it is given.**

Consequences:
1. **A Kimi "CLEAN" is narrower than a Grok or Codex "CLEAN."** It means "no defect in what I was
   shown," not "no defect in the unit."
2. **Reviewer selection bias moves to the architect.** Choosing Kimi's files *is* choosing its blind
   spots. Several P1s in this arc lived at **call sites in files outside the unit** — the two round-2
   P1s were in `MessagingCoordinator`, and the Tor-toggle P1 in `ZitroneApp`. Handing Kimi only the
   `decoy/` package would have hidden all three.
3. **Mitigation:** hand it the call sites and teardown paths too, not just the unit's own files — and
   say in the prompt which files it is *not* seeing, so a clean verdict is correctly scoped.

## One datum worth weighing on the roster

Across U3, **Codex found the top finding in every round; Grok found none of round 3's four P1s** and
twice certified sound a property Codex correctly called P1. The new pair is therefore Kimi plus the
weaker *finder* of the previous pair, with the strongest finder moved to tie-breaker.

That is not an argument against the change — Kimi has just demonstrated real finding ability, and
Codex remains reachable on any dispute. It is an argument for **measuring Kimi's first full round
against the known baseline** rather than assuming the substitution is neutral. Recorded so the
finding curve stays interpretable across the roster change, per the standing calibration rule that a
change in reviewer instructions or roster breaks round-to-round comparability.
