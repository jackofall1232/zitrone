moon: asking kimi-k3...
Before answering: a caveat on my position. I'm the model behind the `moon` wrapper in your setup, but I have no visibility into how `moon` assembles its prompts (headers, separators, ordering) or why interactive CLI mode stalls. I'll answer for "large-context model, single-shot, no tools, sees exactly what you send" — which is what matters. Where `moon`'s plumbing could change the answer, I'll flag it. I also can't diagnose the `-p` stalls from here; treat that as infra, not prompting.

## 1. File selection: how to compensate for no exploration

The single highest-leverage trick: **make me tell you what I couldn't see.** End every prompt with a required section:

```
## MISSING CONTEXT
List any file, symbol, or call site you needed to inspect but were not given,
and what defect class you would have checked for there.
```

This converts my blindness from silent failure into an explicit work item for your next pass. You have repo access; I don't. So the loop should be: I flag missing context → you fetch it → second pass. Two cheap `moon` calls beat one starved one.

Selection heuristics, in priority order:

1. **The unit under review**, complete, with line numbers.
2. **Every call site of its public entry points** — you said defects hide here, so this is non-negotiable. Run the grep yourself and paste *full enclosing functions*, not one-line excerpts. A call-site defect is usually in the caller's lifecycle/threading/error handling, which a snippet amputates.
3. **The type definitions** of everything crossing the unit's boundary (data classes, sealed types, protos). Adversarial bugs live in "caller passes a state the callee's types allow but its logic doesn't."
4. **Concurrency/lifecycle owners**: the class that owns the `CoroutineScope`, `Dispatcher`, lock, or `SharedFlow` the unit touches, even if it never names the unit.
5. **Serialization/trust boundaries**: anything that parses, decrypts, deserializes, or IPCs data the unit consumes.
6. **The tests.** Required for your deliverable anyway ("why existing tests miss it"), and they double as a spec of intended behavior — divergences between tests and code are themselves findings.
7. **A manifest first**: a numbered list of attached files with one line each on role. Cheap, and it lets me cite accurately and notice absence.

If budget forces truncation, truncate *callees* (leaf utilities), never *callers*.

## 2. `--diff` vs `ask` with files

**`ask` with explicit files, and it isn't close — for your stated task.** Adversarial review of a unit needs the unchanged 90%: the caller that was correct before and is now wrong, the lock invariant established 200 lines above the diff hunk. Diffs show what changed; defects hide in what didn't.

`--diff` is the right tool for a different question: "did *this change* introduce a regression?" Use it for PR-gating, not for "find what's broken in this unit."

Best hybrid: `moon ask` with full files, and paste the recent diff *inline at the top* labeled "recent changes — treat as heightened suspicion, not scope." That gives me change-focus without context starvation.

## 3. Prompt structure

Order:

1. **Output contract first** (severity rubric with concrete definitions, the exact verdict-line format, required sections). Parsing instructions beats prose instructions.
2. **The adversarial framing**: "Assume a defect exists and your job is to construct the concrete input/state/interleaving that triggers it. A review that finds nothing is a failed review unless you can argue why the code is sound."
3. **Scope statement**: which file is the unit, which files are context-only.
4. **Prior findings — handled carefully.** Listing them helps me not re-derive them, but it anchors: I'll over-verify your hypotheses and under-search elsewhere. Mitigate by phrasing: "Prior hypotheses below. For each, confirm with a concrete trace or explicitly refute. Then a separate section, HYPOTHESES NOT IN THE PRIOR LIST, is mandatory."
5. **Files last**, each fenced with a path header.

Severity rubric: define P1/P2/P3 by *consequence and triggerability* (e.g., P1 = exploitable/data-loss/security boundary crossed from reachable input; P3 = latent, requires unlikely state). Without this I'll grade on vibes and your severities won't be comparable across runs.

What wastes effort: background about the project, why the review matters, politeness framing, restating the task in multiple phrasings. One hard requirement worth repeating: put the **verdict-line format at both the top and the very end** — instructions at the extremes of a long prompt are followed more reliably than those buried mid-prompt.

Also: **cap the output.** "Max 10 findings, severity-ordered, stop after the verdict line." A truncated review with no verdict is the failure mode you already hit in CLI; an output cap is cheap insurance against the same class of failure.

## 4. Single-shot processing mechanics

- **Inline vs file arguments:** verify what `moon` actually does. If it concatenates files with path headers into one prompt, the two are equivalent and you should use file args (less escaping risk). The thing that matters is that every file has a **path header and line numbers** (`cat -n` style) — my `file:line` citations are only as good as the numbering you give me. If `moon` passes files without line numbers, inlining with your own `path + numbered lines` format is strictly better.
- **Don't duplicate content.** Sending the same file both as arg and inline wastes budget and risks me treating two copies as two files.
- **Long middle is fine for code.** Instructions should live at the start and end; the file bulk can occupy the middle. Don't interleave instructions between files.
- **You're nowhere near the window.** Use that: completeness beats concision. Include the whole caller file rather than the "relevant" function when in doubt — your judgment of relevance is exactly the bias that hides call-site bugs.
- **Ask for the failure sequence, not the reasoning.** "Show the interleaving/input/state that triggers it" forces concreteness and kills speculative non-findings. "Explain your reasoning" produces fluent filler.

## 5. Not worth doing

- Personas ("you are an elite red-teamer"), urgency, stakes language. No measurable effect; pure tokens.
- Exhaustive generic checklists ("check for: injection, XSS, SSRF, CSRF..."). Irrelevant categories dilute attention. Name only the 3–5 defect classes plausible for this unit.
- Asking me to "be thorough." It's non-actionable. "Enumerate every caller of X and check each against invariant Y" is the actionable version.
- Multiple review tasks in one prompt (review + refactor suggestions + docs). Findings quality degrades; split the calls.
- Repeating the rubric more than twice.
- Pre-summarizing the code for me. Your summary embeds your blind spots — which are the same blind spots the bug is hiding in. Give me the code and the manifest; let me build my own model.

## One process suggestion

Shard deliberately. Per-file adversarial passes are cheap and parallelizable; then run one synthesis pass where you hand me the unit + the confirmed findings from the shards and ask only for cross-file interactions. You'll get better cross-file defects from a dedicated synthesis pass than from one giant prompt trying to do both. And it bounds each call's output, which is your actual reliability constraint.

---

# ARCHITECT'S NOTES — what this changes, and one fact verified

Kimi's advice above is its own; below is what it means for this project and what I checked.

## VERIFIED: `moon` passes raw `cat` — NO line numbers

`/usr/local/bin/moon:117-122` builds file context as `===== FILE: <path> =====` + `cat "$f"`.
**So `file:line` citations through `moon` are guesswork.** This is why its `moon`-channel output
cited fewer precise lines than the interactive-CLI runs. **Inline with `nl -ba` (or `cat -n`)
numbering rather than passing bare file args.** Its advice was conditional on this; the condition holds.

## What I have been doing WRONG, in its own terms

1. **Pre-summarising the code.** My prompts open with long prose about what each round changed.
   *"Your summary embeds your blind spots — which are the same blind spots the bug is hiding in."*
   That is the sharpest criticism here and it is correct: I have been handing reviewers my model of
   the change instead of the change.
2. **Verdict format only at the end.** Instructions at **both extremes** of a long prompt are
   followed more reliably. Put the output contract **first** and repeat the verdict line **last**.
3. **No output cap.** *"Max N findings, severity-ordered, stop after the verdict line"* is cheap
   insurance against exactly the truncation that killed two runs.
4. **Anchoring on prior findings.** I list them at length. Correct form: *"confirm each with a
   concrete trace or explicitly refute"* **plus a MANDATORY section for hypotheses NOT in the list.**
5. **Project background and stakes framing.** Waste for Kimi specifically. (Retain for Grok/Codex
   only if evidence supports it — untested.)

## Adopt immediately — the MISSING CONTEXT section

Every `moon` prompt ends with:

```
## MISSING CONTEXT
List any file, symbol, or call site you needed to inspect but were not given,
and what defect class you would have checked for there.
```

**This converts the wrapper's blindness from silent failure into an explicit work item.** I have repo
access; it does not. Loop: it flags what it lacked → I fetch → second pass. Two cheap calls beat one
starved one. Directly addresses the standing risk that *my* file selection is now the binding
constraint on what can be found — and three P1s in this arc lived in files nobody named.

## `ask` with files BEATS `review --diff` for adversarial review

*"Defects hide in what didn't change"* — the caller that was correct before and is wrong now, the
lock invariant established 200 lines above the hunk. `--diff` answers *"did this change regress?"*
(PR-gating), not *"what is broken here?"* **Hybrid:** full files via `ask`, with the diff pasted
inline at the top labelled **"recent changes — heightened suspicion, not scope."**

## File selection priority (its list, and it matches this arc's evidence)

Unit → **every call site of its public entry points, as FULL enclosing functions** → boundary types →
concurrency/lifecycle owners (the class owning the scope/dispatcher/lock, even if it never names the
unit) → trust boundaries → tests → a numbered manifest first.
**If budget forces truncation, truncate callees, never callers.**

Note how well this matches U3: the P1s were in `MessagingCoordinator` (call site), `ZitroneApp`
(lifecycle owner holding `transportLock`), and `MainActivity` (the `lockIf` edge in the deadlock
cycle) — categories 2 and 4 exactly.

## Sharding — the answer to the reliability constraint

Per-file adversarial passes (cheap, parallel) → then **one synthesis pass** given the unit plus the
confirmed shard findings, asked **only** for cross-file interactions. Better cross-file defects than
one giant prompt attempting both, **and it bounds each call's output**, which is the actual failure
mode.

## Its own caveat, worth keeping
It flagged that it cannot see how `moon` assembles prompts and cannot diagnose the `-p` stalls —
*"treat that as infra, not prompting."* A lens stating the limits of its own vantage is the behaviour
to want; the line-number question above is exactly the conditional it flagged, and it resolved
against the wrapper.
