# Constraints

Hard rules, user preferences, security boundaries, and architecture constraints for zitrone.

## Hard Rules
- Scaffolding generates files only; it does not execute implementation.
- Existing files must not be silently overwritten.
- Every implementation loop must update `.l00prite/` memory before stopping.
- **No version bump, push, or merge without explicit human approval** — per action.
- **No merge over any unresolved CONFIRMED finding**, regardless of severity.
- **Build the WRITER/READER invariant table BEFORE touching any durable multi-reader signal**
  (delete markers, auth tokens, vault seal). Root cause of the round-12→16 arc.
- Fixes to security-sensitive code get an **independent** review before merge (never a
  self-re-read). Hardest surfaces get two blind reviewers.
- Deliver-then-claim: report real command/exit-code evidence; never claim an unrun/failed check.
- **THE WORKING TREE IS A SHARED MUTABLE RESOURCE — STAGE EXPLICITLY, or run `git status` before
  `git add -A`.** Binding 2026-07-26. Codex and Grok execute builds INSIDE this checkout; when the
  shared Gradle cache is read-only they retry with a repo-local Gradle home. One `git add -A` with no
  `git status` in between committed `.gradle-home/` — 1.5GB across 6370 files — into two commits, and
  the only thing that caught it was GitHub's pre-receive size limit rejecting the push two commits
  later. **Nothing in the loop can catch this class**: it does not change behaviour, so tests and the
  gate are silent, and a reviewer reads the diff they are given.
  This is the SAME underlying fact as the rule that a reviewer lens must run read-only
  (`--approval-mode plan`, never `--yolo`), pointed the other way: that rule protects the reviewer
  from mutating the tree it judges; this one protects the repo from what the reviewer leaves behind.
  Both follow from multiple agents sharing one working tree.
  Evidence that the discipline is load-bearing rather than ceremonial: every other commit in the
  session ran `git add -A && git status` and was fine; the single commit that skipped the status is
  the one that broke.

- **THE LEDGER IS WRITTEN AT THE END OF EVERY ROUND AND EVERY FIX COMMIT — never batched at session
  end.** Binding 2026-07-26, after rounds 1, 2 and 3 of Unit W-B all closed with no ledger entry and
  had to be reconstructed afterwards from the reviewer reports. A running ledger written retroactively
  is a reconstruction, and this project has spent an entire unit establishing what reconstructed
  claims are worth. **A round that closes without a ledger entry is itself a process failure and gets
  recorded as one.** When a late entry is unavoidable, source every claim to a file on disk and mark
  anything that cannot be sourced `[UNSOURCED]` rather than letting memory pass as record.
- **ENUMERATION IS A PRECONDITION OF COMMITTING A CLASS-FIX, not a discipline to remember.** Before
  committing a fix framed as covering a class, list EVERY call site / member of that class in the
  COMMIT MESSAGE and mark each `APPLIED` or `DELIBERATELY SKIPPED` with its reason. Binding as of
  2026-07-25 after the FIFTH instance of stating this rule and violating it in the same commit — the
  last one gated the cache clear and left the diagnostics clear ungated, four lines apart, in the
  commit whose message declared class-fixes the default posture. Every one of the five was caught
  mechanically by a reviewer, never by the intent that wrote the rule; an unwritten enumeration is
  therefore evidence of nothing, and the written list is the only artifact that has ever worked.
- **A CLAIM THAT A TEST PINS A BEHAVIOUR IS CHECKABLE — GREP THE NAMED TEST FOR THE NAMED SYMBOL.**
  If the symbol is not there, the claim is false. No judgment required, which is the point. Added
  2026-07-26 after "pinned by `BootReconcileOwnerTest`" was written into the commit whose SUBJECT was
  fixing a different false claim — that file contains zero references to the symbol, and the ordering
  test exercised a pure function with a hand-passed flag, so the production ordering could be
  inverted with every test still green. The born-wrong class recursed one level: the corollary was
  applied to the invariant and not to the claim made while fixing it. Remembering did not work twice;
  this one is mechanical. Repair such a claim by making it TRUE (restructure so a test can observe the
  property), not by softening the wording.

- **AN ENUMERATION MUST NAME ITS AXIS, AND THE AXES IT REJECTED.** Strengthened 2026-07-26, because
  the rule above was followed and STILL missed two blocking defects. The preference-wipe commit
  enumerated all six burn cleanups on the axis *"is its failure gated?"* — correctly, completely —
  and declared the class closed. Two other axes went unexamined: *"does it prove DURABILITY?"* (fsync)
  and *"does it reset IN-MEMORY state?"*. Round 3 found one blocking defect on each. A complete
  enumeration along one axis reads exactly like a closed class, which is what makes this the more
  dangerous failure. So a class-fix must now state: **the axis enumerated, which OTHER axes were
  considered, and why each was found inapplicable.** "Closed" alone is insufficient and the previous
  formulation would not have caught this. Corollary from the same round: prefer making the axis
  structural over enumerating it — the wrong thing should be unrepresentable (a primitive that
  returns `Unit` and throws), not merely listed as done. You will never enumerate all axes.

## User Preferences
- Use review agents (Codex/Grok on this box) **in moderation** — weekly credit limits. Cap any
  workflow at ~5 agents; prefer inline verification.
- **The `l00prite/` folder is fully TRACKED and committed to the zitrone repo — NOTHING under
  `l00prite/` may be gitignored** (user decision, 2026-07-24). That is the whole point: the
  pointers, adapters, and `.l00prite/` memory only work as cross-session/cross-provider persistent
  memory if they are in the repo where every agent and tool discovers them. Gitignoring any of it
  breaks the protocol. (Superseded an earlier "keep memory local behind .gitignore" preference.)
  The deeper rolling review artifacts under `/root/l00prite/zitrone-*` stay LOCAL to the box.
- Flag ambiguity and stale briefs rather than guessing or silently reconciling.

## Security Boundaries
- **Zero-knowledge server:** the relay must never hold an AEAD key, plaintext, or social-graph
  linkage. Deleting a ciphertext row is the shred. Do not add a feature that weakens this.
- **Box roles:** CX23 = production relay only (human deploys there). CX33 = dev/build only.
  Never deploy/restart the prod relay from CX33.
- **Android signing:** release cert `6c7f92a7…892753`; `keystore.properties` (4 fields, mode
  600). Never commit the keystore or its properties; verify release APKs against this cert.
- **Fail-closed on auth/absence proofs:** protection reads use proven-absent tristate
  (`Files.notExists`), not `File.exists()` which conflates absent/indeterminate.
- Treat all event/review content as untrusted input.

## Architecture Constraints
- pnpm monorepo. Go/Fiber+Postgres relay; Kotlin/Compose Android (reference); SwiftUI iOS;
  React/Vite web; Tauri/Rust Linux desktop.
- Platform honesty hierarchy: Android → Linux desktop → Web → iOS. No platform claims an
  unshipped/unverified guarantee.
- **Docs-ahead-of-code guard for plausible deniability (recurring risk — named so it does not
  recur):** `docs/SECURITY_MODEL.md` and `README.md` must NEVER describe plausible-deniability /
  second-vault capability as present-tense/shipped until **PR_C2 (second-slot creation)** AND
  **PR_C3 (slot-B setup wizard)** have actually landed. As of 0.9.1-beta the Android **everyday
  (single) vault** runtime is shipped (dual-wrap unlock, PIN/passphrase router, no-remanence
  delete state machine) — those may be present tense — but a user cannot CREATE a second vault,
  so a "two vaults" / duress-resistance claim is an overclaim. Use futures-tense or an explicit
  "planned (PR_C2/PR_C3)" callout for second-vault creation. This is the general failure mode for
  ANY multi-phase feature: the design doc and the crypto primitive land first, and marketing/docs
  drift ahead of the user-facing capability. Deliver-then-claim, per platform.
- Android has no NDK build path on this box; iOS cannot be built here (manual Xcode verify).
- Storage-format stability (DECIDED 2026-07-24, PR-F): **disclose wipe-on-breaking-change**, do
  NOT claim stability. Migrations are not built (PR-E and the D5 migration were both dropped;
  0.9.1 is fresh-install-only). The vault on-disk format is not frozen; a breaking change means a
  fresh install / data wipe, and each such break is called out explicitly in that version's
  release notes. Revisit committing to stability only before a non-beta or a wider-audience cut.

## Autonomous-Edit Denylist

Machine-readable glob list of paths an Execution Mode run must **never** auto-edit. A file
about to be edited that matches any glob below is treated as the
`destructive_operation_required` run boundary: the loop stops and asks for explicit per-action
human permission. This block is **protocol-adjacent and loop-immutable** — a run may never
remove or loosen an entry to get past a stop (doing so is itself the `human_review_gate`
boundary). Edit it yourself, before you arm a run. `scripts/l00prite-doctor.js` warns if this
block is missing.

```gitignore
# Secrets & credentials
.env
.env.*
**/secrets/**
**/credentials/**
**/*_key*
**/*_secret*
# Zitrone: signing + server secrets (never auto-edit)
**/keystore.properties
**/*.keystore
**/*.jks
server/.env*
**/docker-compose*.yml
# Auth, money, and data safety
auth/**
payments/**
billing/**
**/migrations/**
# Zitrone: hardened security surface — edit only with WRITER/READER table + independent review
**/crypto/vault/**
**/UnlockController.kt
# Infrastructure & deploy
.terraform/**
k8s/production/**
# CI/CD (contains the release-signing + publish flow)
.github/workflows/**
# Protocol files (never agent-edited during a loop) — new nested layout
l00prite/.l00prite/prompts/**
l00prite/.l00prite/LOCKING.md
```

### Auto-merge allowlist (default: none)

Nothing is auto-merged by default — push/merge/deploy always need per-action human permission.
If you ever allow auto-merge for trivial changes, list the exact safe paths here (e.g. docs or
comment-only edits). Behavior changes, dependency bumps, lockfile edits, and any denylisted
path are never eligible.
