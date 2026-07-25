# Zitrone vault (0.9.1-beta P1b-2 / PR-D) — rolling ledger

Rolling memory for the Zitrone Android plausible-deniability vault effort. Newest status at top.
Repo: `/root/zitrone` (branch work), reviews + this ledger live in `/root/l00prite/`.
Also mirrored in Claude auto-memory `zitrone-091-vault-track.md`.

## Current position — 0.9.2-beta SECOND VAULT (slot B) + PUCKER BURN — DECISION RECORD (2026-07-24, REVISED)

**main = `55540e3`** (vc17 / 0.9.1-beta CUT + clearnet flip DONE; onion steps deferred to operator).
0.9.1 shipped the everyday (single) vault runtime. **0.9.2-beta = second vault (slot B) + Pucker Burn
duress credential, Android.** `SECURITY_MODEL.md` is ALREADY honest (PR-F, `b7e4b87`); 0.9.2's doc job
is to FLIP status to "two vaults creatable" + document the new limitations/features below.

**⚠️ THIS BLOCK SUPERSEDES the earlier (double-entry, 25%, no-burn) record from earlier the same day.**

### Slot model (REVISED — burn changes the slot layout)
- `SLOT_COUNT = 4`, UNCHANGED (raising to 8 CONSIDERED + REJECTED: triple-entry already gives ~100x
  reduction, realistic use is 1 vault occasionally 2, and a storage-format/IMAGE_VERSION change isn't
  justified for a scenario almost no user hits).
- **Slot 0 = RESERVED for the Pucker Burn credential.** Excluded from blind vault placement entirely.
- **Slots 1–3 = the vault pool.** Blind creation (and the everyday vault A's placement in `create()`)
  operate ONLY over 1–3. → collision probability is now **~1/3 (~33%)**, NOT 25% (OQ2 CORRECTED).
- Slot 0 is sealed BYTE-IDENTICALLY to any vault slot (same Argon2id, same timing, same structure);
  only its CONTENTS differ (a burn marker, not a vault payload). Arm-state is NOT stored anywhere —
  "armed" = "a passphrase can match slot 0," which is exactly what `tryPassphrase` already tests. An
  examiner cannot tell from structure whether slot 0 is armed.

### PR-3 UNIT 2 (docs) — 6-ROUND DOCS-ACCURACY LOOP → ROUND-6 HARD-CAP STOP, HIL DECISION OWED (2026-07-24)
Branch `feat/0.9.2-vault-pr3-unit2-docs` off main (HEAD `18c00f4`, NOT pushed). Flips docs to shipped
reality (second vault creatable via silent triple-entry; per OQ5) + honest disclosures. Paired-blind
docs-accuracy loop (Codex+Grok), every claim checked vs SHIPPED CODE not spec:
- R1: both found 3 overclaims — biometric "A-only/permanent" (WRONG, code is first-enable-wins, my
  contradiction of OQ-A), create "indistinguishable in timing" (create persists=residual), placement
  "uniform"(mod-3 bias). Fixed. R2: two-vs-three understatement (§3.1/§3.2/README) + timing-parity
  success-vs-rejection wording. R3: scope timing/memory-access to the sweep. R4: tests pin derivation
  COUNT not wall-clock; per-slot delete = web/desktop only (Android whole-image) — fixed. R5: Grok
  caught 2 stale "Android runtime not built/pending" (§7/§9) I missed — fixed; Codex re-raised the
  DEFERRED enable-concurrency as a docs overclaim, Grok CONFIRMED the biometric claim ACCURATE →
  resolved to source (never-repoint-ESTABLISHED holds; race is orphan not repoint); I added a disclosure.
- **R6 (CAP): both reviewers CONVERGED on ONE finding — my R5 disclosure re-introduced the "self-heal"
  overclaim I'd already corrected in Unit 1 (failures.md): the key-REPLACED orphan → FAILED (no auto-
  clear); recovery = passphrase + MANUAL disable/re-enable. Corrected (`18c00f4`).** Everything else
  dual-confirmed accurate. **Loop STOPPED at round-6 cap (no round 7); the R6 correction is applied but
  NOT re-reviewed. Per the standing rule only a human authorizes a round past 6 → HIL decides: final
  confirm pass on the correction, or merge as-is.** Reviews: `/root/l00prite/pr3u2-*review-*.md`.
- **HARD-CAP HIL EVENT (2026-07-24, recorded per user):** unit hit round 6; both reviewers CONVERGED on
  one finding (the round-5 self-heal overclaim); correction `18c00f4` applied but unreviewed per the cap.
  Human authorized a SINGLE SCOPED confirm pass (NOT a full round, NOT merge-as-is) — rationale: the
  round-5 version of that exact sentence was the overclaim, so re-checking the one historically-wrong
  sentence is highest-value. **Moonshot (kimi-k3, the round-6 third lens) confirmed the correction CLEAN**
  — recovery accurately user-driven (passphrase + manual disable/re-enable, not auto-heal), no new
  overclaim (`pr3u2-moonshot-result.md`). Option 3 (drop disclosure) explicitly REJECTED by user: a real
  known-gap must be disclosed (Tea-app failure mode), get the wording right instead. **→ clean → push +
  PR + squash-merge on green CI, no version bump, delete branch.**

### ENABLE-ATOMICITY FOLLOW-UP — IMPLEMENTED (Approach B), IN REVIEW (2026-07-24)
Branch `feat/0.9.2-vault-enable-atomicity` off main (HEAD `9e69d58`, NOT pushed). Closes the enable-flow
orphan gap disclosed in Unit 2. **Maintainer decisions:** OQ-1 **Approach B** (per-enable unique Keystore
alias; wrap records `aliasId`; newEncryptCipher creates only its own key, deletes nothing → orphan
impossible by construction, INV-1); OQ-2 **bump the wrap format NOW** (fresh-install stance, no migration);
OQ-3 **OMITTED** — under B a present alias key always opens its own wrap, so the clear-on-AEAD-fail recovery
is moot and would only risk nuking good state on a transient (clean-separation resolution, reported). Docs
(SECURITY_MODEL + VAULT_ARCHITECTURE §3.2) updated IN THIS PR (Unit-2's "orphan user-recoverable via manual
disable/re-enable" → "cannot orphan; failures are absent/invalidated → auto-clear+re-offer"). Invariant
table `/root/l00prite/enable-atomicity-invariant-table.md`. Tests green; no version bump.
**⚠️ STORAGE-FORMAT DECISION (OQ-2, recorded per [[zitrone-storage-format-stability-gate]]):** the persisted
biometric wrap gains a `biometric_vault_alias_id` prefs field (`BiometricWrappedKey{slotIndex, aliasId, blob}`).
NO migration written — a pre-0.9.2 wrap without aliasId reads as not-enabled → user re-enrolls biometric
(passphrase + vault unaffected). Consistent with the 0.9.x fresh-install-only / wipe-on-breaking-change stance
(also: IMAGE_VERSION 2→3 in PR-1). Format history now: image v3 (PR-1) + biometric-wrap-aliasId (this PR).
**REVIEW → CLEAN CONVERGENCE at round 4 (HEAD `eb979db`).** R1 both HIGH: INV-1 failed under disable/GC∥enable
→ `biometricWriteLock` serializes all wrap mutations + enable-commit re-checks `keyExists` under it (abort if
reaped); + cross-slot TOCTOU, honest FAILED docs, 2 LOWs. R2: Codex crash-atomicity *claim* MED vs Grok CLEAN
→ resolved to source (code fine: pre-existing async-prefs/sync-Keystore window, self-heals, missing-key-only by
B's design; CLAIM softened, doc-only). R3: both converged on one stale `reapStaleBiometricAliases` KDoc → fixed.
R4: Codex CLEAN + Grok CLEAN (all invariants verified vs source). **✅ MERGED squash `e32f0aa` (PR #57,
2026-07-24T21:39Z), all CI green, branch deleted, version UNCHANGED (vc17/0.9.1-beta).** Invariant table +
reviews: `/root/l00prite/enable-atomicity-*.md`.
**THIS CLOSED the robustness gap disclosed in PR-3 Unit 1 (concurrent/interrupted biometric-enable orphan).**
The remaining residual — the missing-key crash window (prefs `apply()` async vs Keystore delete sync, two-store
non-atomicity) — is PRE-EXISTING and self-healing (auto-clears via UNAVAILABLE), and is HONESTLY DOCUMENTED in
SECURITY_MODEL/VAULT_ARCHITECTURE, NOT silently deferred. It can only ever be missing-key, never wrong-key (B).

### ✅ CI PIPELINE SECURITY — MERGED squash `c3e4038` (PR #59, 2026-07-24T22:50Z)
All CI green incl. **Security scanning PASS** (the new digest-pinned semgrep gate ran on the clean tree →
0 findings → exit 0: green-on-clean, complementing the throwaway red-on-injection proof). Branch deleted,
version UNCHANGED (vc17). This closed the two standing-hygiene items (SAST silently-green + release-apk
shell-injection). The SAST gate now has POSITIVE proof it fires in CI (throwaway planted injection → Security
FAILED) — replaces the prior silent-green state; the scanner runs and gates, proven not assumed. Two
UNSEQUENCED follow-ups recorded in todos.md (user prioritizes): (1) pin @vN actions to SHAs + Dependabot;
(2) curated SAST language-pack expansion (don't suppress gcm-detection flagging correct crypto). Detail below.

### CI PIPELINE SECURITY (release-apk.yml shell-injection + SAST breakage) — IN REVIEW (2026-07-24)
Sequenced AHEAD of Pucker Burn (a live pipeline hole > an unbuilt feature). Branch `feat/ci-security-hardening`
(HEAD `e61b96f`, NOT pushed). CX33/build-config only, no app code, no version bump. **Decisions:** OQ-S1
vendored `.semgrep/` rules (no registry fetch — gate = repo contents alone); OQ-S2 throwaway-branch CI proof
IN this unit (push authorized, delete-after-red, NOT a merge); OQ-S3 strict early tag-format validation that
gates BEFORE any derivation.
- **Part 1 (injection):** `${{ }}` is substituted into `run:` as text before the shell parses → attacker-
  influenceable values (the release TAG: `github.event.inputs.tag`/`github.ref_name`, and derivations
  `steps.meta.outputs.tag`/`steps.stage.outputs.apk`) reaching 7+ run blocks = code exec in the SIGNING-KEY
  job. Fix: env-var indirection for EVERY `${{ }}`→run (zero remain in any run line; all in env:/with:/ref:) +
  always-quoted `"$VAR"`; strict tag-format validation as the FIRST run step, emitting the tag output only
  after validating `^v[0-9]+.[0-9]+.[0-9]+(-beta)?$` (provably-first, per OQ-S3).
- **Part 2 (SAST):** replaced `semgrep-action@v1 config:auto` (exit 0 on crash/registry-fetch = silent no-op)
  with PINNED `semgrep/semgrep:1.90.0` + `semgrep scan --config .semgrep --error --strict` in a run: step
  (findings fail, config/rule errors fail, crash fails via non-zero run: exit). Vendored base = official
  github-actions SECURITY pack (run-shell-injection catches THIS class) + Go security, gate-clean; full
  Kotlin/TS/JS packs are audit-noisy (gcm-detection etc.) → curated language expansion is a follow-up.
  Rules provenance/license (Semgrep Rules License v1.0, semgrep-rules @81634cf) recorded in .semgrep/README.
- **Local proof:** clean→0, pre-fix release-apk→1 (caught the injection), planted injection→1, broken config→7,
  full-tree fixed repo→0. **PENDING: throwaway-branch CI proof (planted finding → CI red).**
- **NEW FOLLOW-UP surfaced (transparent, not silent):** `github-actions-mutable-action-tag` (unpinned
  `uses: @vN` across all workflows) — a real supply-chain finding the now-working SAST flags; deferred
  because it means pinning every action to a 40-char SHA + SHA-pin maintenance (Dependabot). User to sequence.
- **REVIEW → CLEAN CONVERGENCE round 3 (HEAD `2c339db`).** The app-injection was closed at round 0; every
  round since was GATE COMPLETENESS (can a future injection evade the SAST): R1 both-HIGH — the vendored
  run-shell-injection rule catches only DIRECT `github.*`, misses derived `steps.*.outputs.*` → local
  `no-run-block-interpolation` rule flags ANY `${{ }}`→run; R1 Grok +LOW `::error::` sanitize + digest-pin;
  R2 both — local rule's `${{ ... }}` bounded ellipsis → >10-line multiline bypass → `generic_ellipsis_max_span:
  10000`; R3 Codex CLEAN + Grok CLEAN (Grok empirically: 0–9990 HIT, >10000 MISS — practical ceiling). Each
  round = a real evadable variant, closed + planted-finding-proven. **THROWAWAY-BRANCH CI PROOF DONE
  (user-authorized push, NOT a merge): PR #58 (throwaway→main) planted a derived-output injection; `Security
  scanning` FAILED (semgrep `local.zitrone-no-interpolation-in-run` flagged `run: echo "…${{ steps.meta.outputs.tag }}"`,
  exit 1) while all other checks passed → the gate fires IN CI, not just locally. PR closed + branch deleted, never
  merged.** **CI-security unit = READY-TO-MERGE, HELD for user's merge call** (branch `feat/ci-security-hardening`,
  HEAD `2c339db`, NOT pushed; no version bump). **TWO FOLLOW-UPS surfaced (transparent, user sequences):**
  (1) `github-actions-mutable-action-tag` — pin all `uses:@vN` to SHAs (+ Dependabot); (2) SAST language-pack
  expansion (Kotlin/TS/JS) — full packs audit-noisy (gcm-detection flags the vault's correct AES-GCM); needs
  curated per-language subsets.

### ✅ PR-3 UNIT 1 MERGED — squash `23c9bc4` on main (PR #55, 2026-07-24T19:06Z)
All CI green (TS `lemondrop-crossfamily` 5s-timeout flake passed on rerun — Android-only PR); branch deleted;
version UNCHANGED (vc17/0.9.1-beta). The biometric A-only guard (never-repoint) is on main → unblocks Unit 2's
SECURITY_MODEL "two vaults creatable" flip. **NEXT: PR-3 Unit 2 (docs) off main — silent triple-entry in
VAULT_ARCHITECTURE §3.3/§3.4 (kill wizard language); SECURITY_MODEL two-vaults-creatable + disclosures
(triple-entry + systematic-wrong-pw limit, ~33% blind-overwrite/create, full-pool silent overwrite, biometric
A-only, fail-closed-on-pending-delete); CHANGELOG [Unreleased]; burn permanence DEFERRED to burn PR. Disclosures
checked vs SHIPPED behavior not spec (deliver-then-claim). Then enable-atomicity follow-up.**

### PR-3 SPLIT INTO UNIT 1 (biometric A-only guard) + UNIT 2 (docs) — Unit 1 IN REVIEW (2026-07-24)
**Context correction:** PR-2 already shipped the MainActivity `Created→onUnlockSuccess` mapping, so
second-vault CREATION is LIVE on main today (3× identical new passphrase creates+opens). PR-3 is no
longer what enables creation — it closes the two lagging pieces: OQ4 biometric A-only guard + OQ5
honest docs. **User decisions:** OQ-A **(i) first-enable-wins** — rationale: (ii)/(iii) would store a
durable "slot N is A" pointer = a real-vs-decoy distinction the architecture deliberately never makes;
"A" isn't intrinsic to a slot, just whichever vault was created first; first-enable-wins honors OQ4's
intent (one wrap, never repointed) with zero new state. OQ-B **fast-track Unit 1 as its own PR** (guard
absent while creation is live; units independent). OQ-C **defer burn wording to the burn PR**. **Seq
constraint:** Unit 2's SECURITY_MODEL "two vaults creatable" flip must NOT land before Unit 1 merges.
**Unit-1 refinement (user):** silent suppression can itself leak — enroll surface present in A but
absent in B is a distinguisher. So the A-only rule lives ONLY on the WRITE path; every enroll surface
stays slot-agnostic (global-state-gated) → A and B render identically; a test asserts slot-independence.

**REVIEW ROUNDS 1–4 + REVERT (2026-07-24):** paired-blind Codex+Grok each round. R1: Grok MED F1
(destructive cross-slot refuse — newEncryptCipher deletes the key before the guard) vs Codex INFO →
adjudicated real → gate before key-delete. R2: Codex HIGH+MED (sync-refuse timing oracle + destructive
interrupted re-enable) vs Grok INFO → structural slot-agnostic `isEnabled()` gate (enable only at
no-wrap). R3: Codex HIGH vs Grok INFO (concurrent-enable race) → attempted Activity-scoped single-flight
(`dfba539`). R4: Codex HIGH+2MED (Activity-scoped ≠ global exclusion; sync-throw lockout I introduced;
disable-races-enable) vs Grok CLEAN (residuals LOW/INFO). **Maintainer Option 2:** REVERT the single-flight
(`5cbb292`) — it was ineffective (instance-scoped can't guard a process-shared alias/wrap) AND introduced a
lockout; 3 rounds of edge-case-spawning = wrong approach (D2c/PR-C lesson). **ADJUDICATION (resolve-to-source,
recorded):** Codex R4 HIGH OVER-ESCALATED — "destroys a binding" needs a pre-existing binding, but enable
only starts at `isEnabled()==false`, so worst case is a self-healing orphan wrap (no repoint, no valid-binding
destroy, no A/B tell). Grok correct vs source. Higher-severity label did NOT win by default. **Unit 1 now =
belt never-repoint guard + isEnabled() gate + slot-free enroll predicate** (security invariants hold, both
reviewers). Pre-existing enable-flow concurrency → **dedicated follow-up PR** (atomic/idempotent enable;
process-correct, NOT Activity-scoped) in todos.md; lesson in failures.md. **R5 (reverted delta): Codex CLEAN + Grok
CLEAN → CLEAN CONVERGENCE (both blind, no C/H/M, verified vs source). Lockout gone; A-only guard + security
invariants intact.** One R5 correction (Codex, adjudicated correct vs source, cutting AGAINST my earlier
wording): the concurrent-enable orphan is NOT uniformly "self-healing" — the key-REPLACED variant yields
VaultBiometricResult.FAILED (not UNAVAILABLE) which does NOT auto-clear; recovery = passphrase-unlock +
manual disable (only the key-ABSENT variant self-heals). Still bounded/non-security (no repoint / no valid-
binding-destroy / no A/B tell / no brick) → docs corrected (todos/failures). **PR-3 Unit 1 = READY-TO-MERGE,
HELD for user's merge call** (branch `feat/0.9.2-vault-pr3-unit1-biometric-guard`, HEAD after doc-correction
commit; NOT pushed). Then Unit 2 (docs) + enable-atomicity follow-up.

Branch `feat/0.9.2-vault-pr3-unit1-biometric-guard` off main; guard impl `7670d00`, R1 `c2d8a3c`, R2
`7fbcd89`, R3 `dfba539` (reverted by `5cbb292`), HEAD `80639de` (NOT pushed). Guard changes:
`BiometricUnlockStore.boundSlotIndex()` (reads the same plaintext slot metadata load() already uses,
no new artifact); `VaultUnlockRouter.biometricEnableAllowed(boundSlot, sessionSlot)` pure guard (allow
iff null or same slot; refuse different) — `enableBiometricFromSession` fail-closes on false (seal
nothing, write nothing, no repoint); `VaultUnlockRouter.biometricEnrollOffered(offerPending,
sessionPresent)` slot-FREE enroll predicate wired at the render site. Tests: boundSlotIndex, guard
truth table (bind/same/refuse), enroll-visibility slot-independence. Router+store suites green;
compiles. WRITER/READER invariant table: `/root/l00prite/pr3-unit1-invariant-table.md`. Full spec:
`/root/l00prite/pr3-spec.md`. **NEXT: paired-blind review loop → clean convergence → ready-to-merge
(HELD for user's merge call). Then Unit 2 (docs) as a separate PR after Unit 1 merges.**

### ✅ PR-2 MERGED — squash `374bd44` on main (2026-07-24T17:01Z)
PR #54 squash-merged on user approval; all CI green (Android/Desktop/Go/Server-image/TypeScript/
Security-scan/Vercel); remote branch deleted; local main fast-forwarded. **Version UNCHANGED
(vc17/0.9.1-beta)** — 0.9.2 stays unbumped until the phase completes. main now = PR-1(`2de2bac`) +
PR-2(`374bd44`): triple-entry router fused into the store, uninterrupted-sequence guard live. Reached
via 6-round paired-blind security-review-loop (clean convergence; detail below). **NEXT: PR-3**
(MainActivity no-match→create wiring + biometric-A-only guard + doc reconciliation) — MUST land after
PR-2; then Pucker Burn setup/wipe sibling PRs. NO version bump / push / merge without per-action approval.

### PR-2 IMPLEMENTED + PAIRED-BLIND REVIEW → CLEAN CONVERGENCE (2026-07-24) — READY-TO-MERGE
Branch `feat/0.9.2-vault-pr2-router`, 7 commits (`63b0762`..`30a6c33`). Units 1–4:
`VaultUnlockRouter` triple-entry gate → fused into `attemptUnlockOrAdd` → `PassphraseOutcome` router +
uninterrupted-sequence guard. Security-review-loop, Codex+Grok blind each round, adjudicated vs source:
- **R1** (both): High unsync gate → `@Synchronized`; High biometric-no-reset (Grok) → `publishSession`
  resets on publish; Med cancelled-attempt-keeps-streak → CE reset; Lows (always-compare, recordFailure,
  overflow cap, required resetRitual). **R2** (Codex): High exception-fragile publish reset → `finally{if
  published}`. **R3**: Codex Med hash-outside-lock (false positive, atomic section unchanged) vs Grok CLEAN
  → **reverted** to fully-`@Synchronized` decideCreate rather than override a reviewer on my own analysis.
- **R4 (confirming)**: Codex NEW High **deferred-boundary cancellation** — `withContext` prompt-cancel
  DISCARDS the block's returned `Rejected` (streak kept) + throws CE at the boundary, past the inner catch;
  Grok CLEAN (modeled only the inner catch). Conflict → resolved vs SOURCE (Codex right). FIX `81def41`:
  OUTER `catch(CE){resetCandidate();throw}` around the whole `withContext`; inner catch only re-throws.
- **R5 (convergent)**: BOTH High **rotation re-entry race** — `decideCreate` streak is process-scoped but
  `unlocking` single-flight is composition-local (resets on Activity recreation); a rotation-cancelled
  attempt B's uninterruptible store keeps running while recreated screen starts C, which latches
  `create=true` before B's reset lands → create <3 uninterrupted. Both cited onboarding's `vaultCreating`
  process-scoped single-flight as precedent. FIX `30a6c33`: `AppContainer.tryBeginUnlock()/endUnlock()`
  over a process AtomicBoolean; `attemptPassphrase` claims BEFORE any `decideCreate` (busy→uniform
  `Rejected`, no streak/backoff touch), releases in an OUTER `finally` after the CE-reset catch.
- **R6**: Codex CLEAN + Grok CLEAN, no C/H/M, verified vs source. **CLEAN CONVERGENCE.** Two accepted
  **Info** residuals (documented, not patched): I1 busy-reject µs-vs-Argon2 timing (concurrency/liveness
  signal the operator already induced; not a passphrase/vault oracle, never advances the gate); I2 no
  post-rotation busy spinner (UX only). Compiles; `VaultUnlockRouterTest`+`AutoLockDecisionTest` green.
  **HELD at ready-to-merge — no push/merge/version-bump without explicit per-action user approval.**
  Review prompts+outputs: `/root/l00prite/pr2-fix{,2,3,4,5}-review-{prompt,codex,grok}.md`.

### Multi-phase PR plan (unchanged shape; PR-1 now burn-AWARE)
- **PR-1** — `VaultImageStore.attemptUnlockOrAdd(...)` fused writer, **burn-aware** (slot-0 match →
  `Burn` outcome; blind placement over 1–3 only). Heaviest review (new writer to the image + new
  interaction with the round-13–16 delete-marker machine). Companion: `create()` must also place A in 1–3.
- **PR-2** — router fusion + timing parity + **triple-entry gate incl. uninterrupted-sequence guard**.
- **PR-3** — UI wiring; biometric A-only guard; doc reconciliation.
- **Sibling PRs (Pucker Burn feature):** burn setup UX (settings entry, permanence ack), burn wipe
  execution. Scope/sequencing TBD; PR-1 only makes the store burn-AWARE, it does NOT build setup/wipe.
- Review intensity: between D3 and D2c, LEAN per [[workflow-agent-budget-discipline]] (≤5 agents, one
  skeptic pass, free Codex/Gemini for breadth).

### SIX ORIGINAL OPEN QUESTIONS — RESOLVED (with revisions)
- **OQ1 (typo/confirmation gate) — REVISED TWICE: single → double → TRIPLE-entry.** Original locked
  decision was single-entry (any new passphrase creates). My plan recommended double-entry. **User now
  mandates TRIPLE-entry of the IDENTICAL non-matching passphrase, consecutively + UNINTERRUPTED.**
  Mechanism: att-1 no-match → record candidate (RAM), count=1, fails normally. att-2 EXACT match to
  candidate → count=2, fails normally; NON-match → new candidate, count RESETS to 1 (never accumulate
  across different strings). att-3 EXACT match at count=2 → CREATE. Any mismatch → reset to count=1 w/
  the new string as candidate. Any match vs an EXISTING slot at any point → normal unlock, sequence
  discarded. **NEW GUARD: the sequence must be UNINTERRUPTED — app backgrounding, a lock cycle, or
  process death RESETS candidate+count to zero.** This targets SYSTEMATIC wrong-password entry (a user
  confidently retyping one old password across sessions), which the repetition math does NOT protect
  against. **Rejected alt #1 (recorded, do not revisit):** "3 consecutive failures of ANY kind creates"
  — unrelated typos accumulate more easily than one exact string repeating, so it would INCREASE
  accidental creation. **Rejected alt #2 (recorded):** on-screen warning after the 2nd identical failure
  — itself a discoverable artifact (an adversary types garbage twice to surface it, revealing that
  unrecognized passphrases DO something); the uninterrupted-sequence guard gives the same protection
  with NO disclosure. **Risk math (ESTIMATES, not measurements; assumes RANDOM typos):** P(destroy an
  existing vault per accidental-typo event) ≈ single 0.25 → double 0.025 → triple 0.0025 (~100x vs the
  original locked decision). HONEST CAVEAT: for SYSTEMATIC wrong-password entry the repeat probability
  → 1 and the gate barely helps; the uninterrupted-sequence guard is the only (partial) mitigation there.
- **OQ2 (blind-overwrite) — CORRECTED to ~33%.** With slot 0 reserved, blind placement is over 3 slots
  → ~1/3 chance of overwriting an existing vault per creation. Accept as documented VeraCrypt-model
  tradeoff (do NOT raise SLOT_COUNT). Update any prior "25%" figure in ledger/docs.
- **OQ3 — UNCHANGED.** `destroy()` stays WHOLE-IMAGE delete; document that deletion removes the device's
  identities (plural). B-creation clears a stale `vault.delete-intent` the same way `create()` does.
- **OQ4 — UNCHANGED.** Suppress biometric-enable from any non-A-bound session (keep the single wrap A-bound).
- **OQ5 — UNCHANGED.** Update `VAULT_ARCHITECTURE.md §3.3/§3.4` + `PR_C3 wizard` ref → silent-router +
  triple-entry (NOT a wizard); destruction documented as its own future phase.
- **OQ6 — UNCHANGED.** Real outcomes {unlock, create, confirmation-reject}; NO stored occupancy count, ever.

### NEW FEATURE — PUCKER BURN (duress credential) — replaces rejected "N wrong passwords wipes"
- **WHY it's separate:** the original "N wrong passwords wipes the device" idea was REJECTED — it
  collides with vault creation (both consume the SAME signal, repeated unrecognized passphrases), so a
  user deliberately creating vault B would be partway through a self-destruct. Burn therefore gets its
  OWN dedicated credential in reserved slot 0.
- **Trigger:** `tryPassphrase` runs across all slots incl. 0; a match on slot 0 triggers a FULL WIPE
  instead of unlock. Works FROM THE LOCK SCREEN (the point of a duress credential) — fires on the FIRST
  entry of the burn passphrase (a match wins over triple-entry; it never accumulates).
- **Deniability rationale (recorded, do not relitigate):** slot 0 being positionally reserved is an
  ACCEPTABLE disclosure. Burn and deniability defend different adversaries — deniability hides HOW MANY
  identities exist (A-vs-B); slot 0 reveals only that a burn FEATURE exists (public knowledge, like
  Signal registration-lock docs). It does NOT reveal whether slots 1–3 hold one vault or three, and
  slot 0 can't be opened or its arm-state determined without the passphrase.
- **Setup UX:** Settings entry "Pucker Burn Password Setup" placed directly ABOVE "Delete Account".
  Once configured the entry DISAPPEARS from settings PERMANENTLY (only "Delete Account" remains) — feature
  existence is public but ARM-STATE is not, so an examiner browsing settings can't tell if it's set. Copy
  for the two entries must be clearly DISTINCT so burn-setup is not confused with account deletion.
- **Permanence:** the burn password is PERMANENT once set — cannot be changed or removed (zero-knowledge
  consequence; also correct for duress — a changeable burn password is one more thing an adversary can
  coerce a change to). Warning must be ACTIVELY ACKNOWLEDGED (same rigor as the vault passphrase warning),
  making THREE things unmissable: (1) never changeable/removable, (2) entering it wipes everything
  IMMEDIATELY with NO confirm prompt, (3) forgetting it leaves slot 0 permanently occupied.

### NEW DOCUMENTATION OBLIGATION (SECURITY_MODEL.md) — deliver-then-claim
- Full-pool silent overwrite: a user who deliberately fills slots 1–3 will, on any further creation,
  overwrite an existing vault WITH CERTAINTY and NO warning (the ZK constraint means the app cannot
  detect/report a full pool). The burn slot is protected (reserved) so DURESS protection survives, but
  vaults do not.
- The ~33% blind-overwrite probability per creation event.
- Triple-entry creation gate + its honest limitation vs SYSTEMATIC (not random) wrong-password entry.
- Burn password permanence + non-recoverability.

### STILL OPEN — DO NOT DECIDE, recorded as OPEN
1. **Wipe scope:** what does the burn "factory reset" concretely destroy — local slots only (all vaults
   + burn) or also the relay account(s)? Look like an ordinary app reset, or less conspicuous? UNDECIDED.
2. **Burn ↔ delete-state-machine:** does the burn wipe interact with the D2c rounds-13–16 account-delete
   state machine, or stay entirely separate? Flagged as needing ANALYSIS, not assumed.
3. **[claude-surfaced] 0.9.1-image incompatibility:** slot-0 reservation is a PLACEMENT-semantics change
   — a 0.9.1 image with vault A at slot 0 (~1/4 of them) would be read by 0.9.2 as a BURN match → WIPE.
   0.9.2 must NOT misinterpret old images. Recommend bump `IMAGE_VERSION` 2→3 so old images fail-CLOSED
   (route to fresh onboarding, not silent burn); consistent with fresh-install-only stance. NEEDS USER
   DECISION — flagged in the PR-1 spec, not decided here.

### TIMING-PARITY RE-VERIFY (3-attempt model + burn slot 0) — PASSES (full analysis in the PR-1 spec)
Every attempt does the IDENTICAL store work as an ordinary single failed unlock: **5 Argon2id (4-slot
sweep INCLUDING slot 0 + 1 unconditional candidate seal) + exactly one 256 KiB payload GCM + 1 tiny
wrapped-key GCM**, regardless of outcome (unlock / BURN / create / reject) or position (att 1/2/3). Slot
0 in the sweep adds NO asymmetry (it was already 1 of SLOT_COUNT; armed-vs-unarmed slot 0 is the same
Argon2id + GCM as any real-vs-filler slot — the core deniability property). Burn-match and vault-match
are timing-identical pre-outcome (both open one 256 KiB payload; the wipe, like the unlock, is
post-outcome). Blind-placement exclusion of slot 0 is a trivial index computation (`1 + randomIndex(3)`)
— no timing/IO signature; the whole ~1 MiB image is rewritten on every create so write-IO reveals no
slot. Triple-entry counter lives in router RAM (SHA-256 + constant-time compare, ~µs). Sole residual:
create persists synchronously (~tens of ms, post-outcome, under ~1 s KDF) — not worsened by any of this.
CRITICAL constraints unchanged: tryPassphrase ONCE (not via addVaultToImage → 8 derivations); NO
unlockImage verify on create; candidate seal + payload GCM are load-bearing filler (comment + test).

### PR-1 SPEC APPROVED → IMPLEMENTATION AUTHORIZED (user, 2026-07-24T11:52Z)
Spec `/root/l00prite/pr1-attemptUnlockOrAdd-spec.md` approved as written, WITH:
- **BLOCKING (in-scope for PR-1, not a follow-up): §10.1 resolved as option (a)** — bump
  `IMAGE_VERSION` 2→3; a v2 image routes to FRESH ONBOARDING. Requires a NEW branch in `open()`
  distinguishing known-old-version from `CorruptImage` (which today escalates); that read-path branch is
  part of PR-1's diff and gets its OWN test: v2 image → onboarding, NOT CorruptImage, NOT any slot-0
  interpretation. attemptUnlockOrAdd's slot-0 semantics must NOT land before this — a v2 image with A at
  slot 0 would WIPE on the user's own correct passphrase. **WHY this ships despite 0.9.1 being
  fresh-install-only with no real users (recorded per user): the blast radius is currently just test
  devices, but "we happened to have no users" is NOT a safety property — the fix ships regardless.**
- **Review-scope amendment 1:** invariant 6 (marker clear on create cancels A's pending
  delete-reconcile) gets a FULL writer/reader enumeration in review, not the abbreviated argument — the
  claim "only intent-only markers realistically appear at a lock screen" is exactly the
  reader-assumes-a-marker's-meaning shape that produced the round-12 and round-15 defects. Enumerate
  EVERY writer of both markers + every reader's assumption; prove it holds for every writer state incl.
  mid-write crash (rounds-13–16 discipline).
- **Review-scope amendment 2:** the dropped `unlockImage` re-verify (§4) needs an explicit answered
  VERDICT in review — are the non-KDF size checks sufficient to catch a malformed sealed slot before a
  key is handed to a session; if not, what is the cheapest verify preserving timing parity.
- Explicitly approved as written: corrupt-burn-payload asymmetry (burn fires even on damaged slot-0
  payload); 5-Argon2id parity across 4 outcomes × 3 attempt positions; slot-0 placement exclusion; ~33%.
- **Better-solution check (user asked; answered):** lazy in-place migration (first successful v2 unlock
  relocates a slot-0 vault into 1–3, rewrites v3, preserves data) CONSIDERED + REJECTED — builds a
  migration path the project explicitly doesn't build, adds a new writer + a first-unlock write
  timing/IO asymmetry (a distinguishable event), for test-device-only benefit. Option (a) stands.

### PR-1 IMPLEMENTED — GREEN, LOCAL COMMIT, AWAITING REVIEW DISPATCH (2026-07-24)
Branch `feat/0.9.2-vault-slotb-pr1`, local commit **`321b358`** (NOT pushed). Files: VaultImageStore.kt
(+288), VaultSlots.kt (+44), VaultImage.kt (+20), ZitroneApp.kt (+14), MainActivity.kt (+28), new
AttemptUnlockOrAddTest.kt (+438). What landed, all per the approved spec:
- `attemptUnlockOrAdd(passphrase, genesis, create): UnlockOrAdd{Unlocked,Burn,Created,Rejected}` — one
  tryPassphrase over all SLOT_COUNT slots + 1 unconditional candidate seal (=5 Argon2id) + exactly one
  256 KiB payload GCM, every outcome; slot match wins over create; slot 0 → Burn (store writes nothing);
  create seals into a VAULT-POOL slot (never 0) reusing the existing DEK, clears both markers first; no
  unlockImage re-verify; NotDurable advances canonical then throws.
- Slot-0 burn reservation: `BURN_SLOT_INDEX`/`VAULT_SLOT_RANGE`/`randomVaultSlotIndex` single source of
  truth; `createVaultSlots` places A in 1..SLOT_COUNT-1; `addVaultSlot`/`addVaultToImage` marked
  burn-UNAWARE (dormant).
- `IMAGE_VERSION` 2→3; `open()` → `VaultImageException.LegacyImage` for v2 (distinct from CorruptImage)
  BEFORE any slot use; `isLegacyImage()` peek + `retireLegacyImage()` (re-proves v2, refuses current).
  App: container `isLegacyImage()` delegate, `createVaultAndPublish` retires-then-creates, MainActivity
  off-main precompute routes v2→onboarding + a LegacyImage backstop in the unlock-failure path.
- Tests (all pass): crypto-budget PARITY across unlock/burn/create/reject (5 deriver + 1 payload GCM + 5
  wrapped GCM each; outer GCM only on create); burn incl. corrupt-marker-still-fires + wins-over-create;
  placement-excludes-slot-0; forced blind overwrite; marker clear; NotDurable+canonical-advanced; v2
  LegacyImage/isLegacy/retire-refuses-current. **Full app unit suite GREEN; assembleDebug + assembleRelease GREEN.**

### PR-1 REVIEW COMPLETE — TWO BLIND REVIEWERS, BOTH REJECT (2026-07-24)
Codex + Grok run blind (`/root/l00prite/pr1-review-{codex,grok}.md`, prompt `pr1-review-prompt.md`).
**Both verdict: NOT MERGE-CLEAN.** Strong convergence on 2 blocking issues; each also caught items the
other missed (the D2c two-reviewer pattern held). All findings VERIFIED against source by me.

**BLOCKING — B1 (Critical/High, BOTH reviewers): marker clear over a LIVE image.** `attemptUnlockOrAdd`
Created (:684–692) clears BOTH delete markers whenever either is present, while A's image is still live.
Root cause: OQ3's "clear like `create()`" is UNSAFE — `create()` clears only under `require(!binFile.exists())`
(image ABSENT → markers genuinely stale); the add path clears while A is LIVE (markers NOT stale, they own
A's in-flight delete). Two sub-defects: (a) clearing `vault.delete-confirmed` + a crash before the image
write → `{no markers, A image}` → boot routes Locked not DeleteIncomplete → server account gone but the
decryptable local image PERSISTS forever with no destroy authorization (no-remanence DoD fails); (b)
clearing `vault.delete-intent` (reachable intent-only at a lock screen) silently cancels A's account-delete
reconcile → A's server account never deleted; +if B's ~1/3 blind placement overwrites A, A is locally
destroyed AND its delete forgotten → account undeletable from device. **This is exactly the
reader-assumes-a-marker-meaning shape that produced the round-12/15 P1s.** **FIX NEEDS USER DECISION
(reverses OQ3):** recommend the Created branch must NOT clear a live image's markers — instead FAIL-CLOSED
(refuse to create / return Rejected, still doing the throwaway payload GCM for parity) when ANY delete
marker is present. That preserves A's delete state AND removes the stale-confirmed→auto-destroy-B risk that
originally motivated the clear.

**BLOCKING — B2 (High/Medium, BOTH): dropped `unlockImage` re-verify is INSUFFICIENT (explicit verdict,
both).** Created (:718) builds `VaultOpen` from in-memory `candKey`+genesis with no read-back; size checks
bound layout only, not seal invertibility/placement. A miscomputing (right-size/wrong-content) `aeadEncrypt`
→ B written durably, session works this run, B PERMANENTLY unopenable after process death. **FIX (no user
decision, both propose the SAME):** AEAD-decrypt `candSlot.wrapped` with the candidate's still-live master
key and constant-time-compare to `candKey` — 0 extra Argon2id (needs `sealSlot` to expose/retain the master
key briefly); optional `openPayload(candKey, sealedGenesis)` = one create-only 256 KiB GCM (accepted
residual class). Do NOT re-run tryPassphrase/unlockImage (+4 Argon2id, breaks parity).

**ALSO REAL (one reviewer each, verified):**
- **F4 (Codex, Medium): wipe gap on throw.** `candKey` + a live `unlock.vaultKey` are created BEFORE the
  `try` (:649–651); a throw in `randomVaultSlotIndex`/`sealSlot` (native crypto fail/OOM) bypasses the
  catch (:731) → live keys stranded in heap. Grok MISSED this. Fix: generate inside the try / broaden wipe.
- **F6 (Grok, Low): exception-path parity.** A non-durable marker clear throws NotDurable BEFORE
  `sealPayload` → that path does 5 Argon2id + 5 wrapped GCM but skips the 1 payload GCM. Codex missed it.
  Resolves with the B1 restructure.
- **F9 (Grok, Low/latent): `unlockWithKey` accepts `slotIndex==0`** (`require(slotIndex in 0 until SLOT_COUNT)`)
  → a future biometric wrap naming slot 0 would open the burn payload as a vault instead of wiping. Latent
  (biometric is A-only/1..3 today). Add a `VAULT_SLOT_RANGE` guard, or defer to the burn/biometric PR.
- **Low (both): `addVaultSlot`/`addVaultToImage` burn-unaware** dormant primitives (already kdoc-warned).
- **Info (Grok): burn==vault passphrase → Burn wins** (first-match, slot 0 checked first) — burn-setup PR concern.
- **Doc (both): spec §5 table undercounts wrapped GCM** — says "1 tiny wrapped GCM", actual is 5 (4 sweep
  unwraps + 1 candidate seal); the TEST is correct; parity holds. Spec table corrected 2026-07-24.

**CLEAN (both reviewers): corrupt-payload asymmetry (vault→CorruptImage, burn-on-damaged-marker→Burn,
implemented as specced); §10.1 legacy isolation (v2 can NEVER reach slot interpretation via open/unlock/
unlockWithKey/decodeImage; `retireLegacyImage` re-proves v2 under imageLock, cannot delete a v3); KDF+payload
timing parity (5 Argon2id + 1 payload GCM across all 4 outcomes; armed-vs-unarmed slot 0 identical);
slot-0 placement leaks nothing; wipe on normal branches; lock order; DEK reuse (no {bin,no-dek} brick).**

**PR-1 BLOCKED — no merge over unresolved finding.** Owed before any fix round: (1) USER DECISION on B1
(reverse OQ3 → fail-closed-on-pending-delete). Then a fix commit: B2 (candidate self-verify) + F4 (wipe) +
F6 (folds into B1) + F9 (guard). Then re-review the fixes. NO push/merge/version bump without approval.

### B1 DECISION — CONFIRMED fail-closed, reversing OQ3 (user, 2026-07-24)
**Recorded as a DECISION defect, not just implementation (OQ3 was my error).** `create()` clears markers
only because `require(!binFile.exists())` has already PROVEN them orphaned — that precondition IS the
proof. `attemptUnlockOrAdd` has no equivalent: with a live image a confirmed marker may be stale or live
and nothing observable distinguishes them; any guarded-clear is a heuristic dressed as an invariant.
Fail-closed avoids ever acting on a distinction the code cannot make. **FIX (this commit):**
- **B1:** if ANY delete marker is present-or-indeterminate, the Created branch does NOT create and does
  NOT clear — returns `Rejected` (NOT throw), still doing the throwaway payload GCM (identical crypto to
  an ordinary wrong password; folds in F6 — no path skips the payload GCM; no mid-delete side channel).
  Marker check + write are in the SAME `imageLock` critical section as the sweep (no TOCTOU — B1 req 3).
  Full WRITER/READER invariant table under the new semantics: `/root/l00prite/pr1-fix-marker-invariant-table.md`
  — key result: the add path is REMOVED from the marker-writer set → rounds-13–16 machine unchanged, no
  reader's assumption can be falsified by it.
- **B2:** new `sealSlotSelfVerifying` (VaultSlots.kt) — decrypt-and-constant-time-compare the wrapped key
  to the vault key under the SAME derived master key (0 extra Argon2id, 1×60 B GCM), master key wiped in
  finally (lifetime unchanged from sealSlot). Used unconditionally for the candidate seal → wrapped-GCM
  count is 6 for ALL four outcomes (parity clean); throws on a miscomputing AEAD provider before Created.
- **F4:** `candKey` generated INSIDE the try (mirrored in a cleanup var); catch wipes both it and a live
  `unlock.vaultKey` — no throw path strands live key material.
- **F9:** `unlockWithKey` now `require(slotIndex in VAULT_SLOT_RANGE)` (rejects slot 0); + tightened
  `BiometricUnlockStore` accepted range to `VAULT_SLOT_RANGE` so a tampered slot-0 wrap reads not-enabled
  and never reaches `unlockWithKey` as a throw (preserves that store test's documented invariant).
- **Doc:** SECURITY_MODEL.md discloses the accepted cost (creation silently fails while a delete is
  pending — rare, transient, leaks nothing). Spec §5 wrapped-GCM correction kept (now 6 with the verify).
**Then STOP** — report, user dispatches re-review. No self-dispatch. No push/merge/version bump.

### PR-1 FIX ROUND IMPLEMENTED — GREEN, LOCAL COMMIT `9ab8cb0` (2026-07-24)
On top of `321b358`. Files: VaultImageStore.kt (attemptUnlockOrAdd rewrite + unlockWithKey guard),
VaultSlots.kt (+sealSlotSelfVerifying), BiometricUnlockStore.kt (range→VAULT_SLOT_RANGE),
SECURITY_MODEL.md (disclosure), + 3 test files. All per the B1 decision + invariant table above:
- **B1 fail-closed:** attemptUnlockOrAdd writes/clears NO marker; if it can't prove both absent →
  Rejected (not throw) + throwaway payload GCM; A's delete machine untouched; check+write one imageLock
  section (no TOCTOU); F6 folded. **B2:** sealSlotSelfVerifying (0 extra Argon2id, +1 wrapped GCM, master
  key lifetime unchanged) throws before persisting an unopenable slot; wrapped-GCM now 6 across all 4
  outcomes. **F4:** candKey + matched unlock.vaultKey wiped on any throw (candidate inside the try).
  **F9:** unlockWithKey requires VAULT_SLOT_RANGE (rejects slot 0); BiometricUnlockStore range tightened
  to match (tampered slot-0 → not-enabled, never reaches the guard as a throw).
- New/updated tests: fail-closed on intent AND confirmed (marker untouched, nothing written); self-verify
  throws+persists-nothing on a mis-sealing provider; parity=6 wrapped; unlockWithKey/biometric slot-0
  rejection; VaultPrimitive/VaultImageStore/BiometricUnlockStore all pass. **Full app unit suite +
  assembleDebug + assembleRelease GREEN.**
**STOPPED — no self-dispatch re-review.** Owed: user dispatches a re-review of `9ab8cb0` (the diff since
`321b358`) — the delta touches the marker-writer-removal + the self-verify seam. NO push/merge/version bump.

### PR-1 FIX RE-REVIEW — TWO BLIND REVIEWERS, BOTH PASS (2026-07-24)
Codex + Grok blind on delta `321b358..9ab8cb0`, 7 binding items, guilty-until-proven (`pr1-fix-review-{codex,grok}.md`,
prompt `pr1-fix-review-prompt.md`). **Both PASS — NO Critical/High/Medium runtime defects.** All 7 items
CLEAN under independent re-derivation: (1) B1 attemptUnlockOrAdd is a PURE marker-reader (both enumerated
every marker-touch line; none reachable), Rejected-not-throw + throwaway payload GCM on marker-present;
(2) TOCTOU closed (one uninterrupted imageLock; marker writers take it too; reentrant open()); (3) B2
self-verify constant-time (MessageDigest.isEqual, equal-length), master-key lifetime unchanged, finally-wipe
on all paths, throws before any persist; (4) parity 5 Argon2id / 1 payload GCM / 6 wrapped GCM across all
outcomes+positions (marker-reject = reject budget, no outer); (5) F4 no strand, mirror aliases via also;
(6) F9+biometric A-only preserved, no legit A on slot 0, 0.9.1→v2 upgrade traced (old slot-0 biometric
reads not-enabled but no access loss beyond the deliberate v2 retire).
**Findings (all non-blocking):** G1 (Low, BOTH) stale attemptUnlockOrAdd KDoc still described the REMOVED
OQ3 marker-clear behavior — FIXED (commit `296ebc6`). G2 (Info, Grok) parity test name lag + marker-present
budget untested — FIXED (renamed + added marker-reject measurement, `296ebc6`). **G3 (Info, BOTH) — OPEN
DECISION FOR USER:** B2 self-verify is WRAPPED-KEY-ONLY; the PAYLOAD layer is not round-tripped, so a
miscomputing AEAD *payload* op could persist a durable-but-unopenable-after-restart vault (session works
this run via the in-memory genesis copy). Both reviewers classify this as an ACCEPTED RESIDUAL under the
one-payload-GCM budget (not a defect) — it's the "optional openPayload verify" half of B2 the user scoped
OUT last round (wrapped-key-only mandate). Closing it = add one create-only `openPayload(candKey,
sealedGenesis)` (256 KiB GCM, accepted residual class). AWAITING user: accept-as-residual (documented) vs
add the payload verify.
**State: commits `321b358` + `9ab8cb0` (fix) + `296ebc6` (cleanups), local branch only, NOT pushed.** Both
re-review reports clean. **READY FOR USER'S MERGE CALL** (no merge/push/version bump without approval),
pending the G3 accept/close decision. Then PR-2 (router + triple-entry) or the burn setup/wipe spec.

### G3 CLOSED — payload self-verify added (user decision, commit `8f4545d`, 2026-07-24)
User elected to CLOSE G3 (not accept the residual). Rationale (user): the failure is silent, only manifests
after process death, gives a full working session before the vault becomes permanently unopenable — the
worst shape; the cost (one create-only 256 KiB GCM) is inside the already-accepted create-persist residual,
touches no other outcome (parity + 5-Argon2id unaffected), and is consistent with the B2 mandate (verifying
the wrapped key but not the payload closed only half the door). **Impl:** attemptUnlockOrAdd Created
(markers-absent) now, after `sealPayload(candKey, genesisPayload)`, does `openPayload(candKey, sealedGenesis)`
+ CONSTANT-TIME-COMPARE (`MessageDigest.isEqual`) against genesisPayload + wipes the decrypted copy — NOT
merely "decrypt succeeded" (a self-consistent-but-wrong-content box must fail); throws before any persist.
Create payload GCM now = 2 (seal + verify); unlock/burn/reject/marker-reject unchanged = 1; wrapped = 6,
Argon2id = 5 across all. Tests: MisSealingPayloadOps (self-consistent, wrong content) → create throws +
persists nothing; parity test updated (create=2 payload) + confirms other budgets unchanged. KDoc + spec
§5 table updated. **Full unit suite + assembleRelease GREEN.**
**Branch now: `321b358` + `9ab8cb0` + `296ebc6` + `8f4545d`, LOCAL only, NOT pushed. STOPPED — user will
re-review the payload-verify seam (`296ebc6..8f4545d`, or just `8f4545d`) with the same two blind reviewers
BEFORE merge (a fix introducing a defect is this codebase's recurring pattern). NO merge/push/version bump.**

### G3 DELTA RE-REVIEW — TWO BLIND REVIEWERS, BOTH PASS (2026-07-24)
Codex + Grok blind on `296ebc6..8f4545d`, 6 binding items (`pr1-g3-review-{codex,grok}.md`, prompt
`pr1-g3-review-prompt.md`). **Both PASS — NO Critical/High/Medium.** All 6 CLEAN under re-derivation:
(1) content compare (equal-length constant-time MessageDigest.isEqual; MisSealingPayloadOps genuinely hits
the MISMATCH branch, not null); (2) throw-before-persist (verify precedes encodeImage/outer-encrypt/
atomicWrite/canonical/DEK; nothing partial on failure); (3) wipe (verifyPt finally on all paths incl. throw;
candKey via F4 catch; no use-after-wipe; verifyPt needs no F4 mirror — no throwing statement between its
alloc and its own try); (4) non-create parity unchanged (unlock/burn/reject/marker-reject all 1 payload / 6
wrapped / 5 Argon2id; create alone 2 payload; marker-reject source-identical to ordinary reject);
(5) new defects — none Crit/High/Med; (6) KDoc + parity test + §5 TABLE accurate.
**Findings (all non-blocking, applied — commit `be18911`):** Codex F1 (Low) KDoc "not a per-outcome
distinguisher" imprecise → reworded (create IS observable post-outcome but within the accepted create-persist
residual; not a KDF distinguisher; marker-present create still = reject budget). Codex F2 + Grok G3-L1 (Low,
CONVERGED) stale spec §3/§4/§8 sketches (§4 still showed the REMOVED pre-B1 marker-clear + non-self-verifying
sealSlot — the G1 hazard class) → SUPERSESSION BANNER added atop the spec directing to the authoritative
record (code KDoc + §5 table + ledger). Grok I2 (Info) null-open arm untested → added CorruptPayloadBoxOps
test (tag-corrupt box → openPayload null → throw, nothing written). **Grok I1 (Info) — NOTED RESIDUAL, not
fixed:** the OUTER image DEK seal is still "encrypt once, write" (not self-verified) — a pre-existing create()
residual, and a FUNDAMENTAL same-provider limit (encrypt-then-decrypt under a uniformly-broken provider proves
nothing); G3 closed the payload half only. Not a regression; documented here.
**PR-1 IS NOW FULLY REVIEW-CLEAN.** Every reviewed seam PASSed both blind reviewers: `321b358..296ebc6`
(fix round) and `296ebc6..8f4545d`+`be18911` (G3). Branch `feat/0.9.2-vault-slotb-pr1` =
`321b358`+`9ab8cb0`+`296ebc6`+`8f4545d`+`be18911`, LOCAL only, NOT pushed, no version bump. Full unit suite +
assembleRelease green. **READY FOR USER'S MERGE CALL.** Then PR-2 (router + triple-entry) or burn setup/wipe.

### ✅ PR-1 MERGED (user-approved, 2026-07-24). PR #51 → squash `2de2bac` on main.
Pushed `feat/0.9.2-vault-slotb-pr1`, opened PR #51, ALL 8 CI checks green (Android build+unit 4m33s,
Desktop-Linux, Go, Security-scanning, Server-image, TypeScript, Vercel×2), squash-merged, remote branch
deleted. **VERSION UNCHANGED — vc17 / 0.9.1-beta** (per standing discipline: 0.9.2 stays unbumped until the
phase completes, PR-2 + PR-3 minimum). main head = `2de2bac`. Store-layer only; `create=true` has NO caller
until PR-2's router → nothing new reachable by users post-merge.

### PR-2 SPEC DELIVERED — awaiting user review before implementation (2026-07-24)
`/root/l00prite/pr2-router-triple-entry-spec.md`. Router fusion + triple-entry gate + uninterrupted-sequence
guard. WRITER/READER invariant table FIRST for the RAM-only candidateHash/candidateCount; proof that
create=true fires ONLY on 3 uninterrupted identical entries; rapid-cycle-can't-defeat proof (every onStop
resets); candidateCount separate from backoff failedAttempts (Burn feeds neither); SHA-256+constant-time
compare every attempt (no distinguisher); NotDurable UX; genesis encode+wipe. Records the SEQUENCING
CONSTRAINT (PR-3 MainActivity wiring must NOT precede PR-2 — else creation reachable on a single unrecognized
passphrase). 3 open questions (reset-hook placement, Burn-until-wipe handling, PassphraseOutcome sealed type).
**NO implementation until user reviews the spec (same gate as PR-1). NEXT: PR-2 spec review → impl.**

---

## Current position (2026-07-24) — 0.9.1-beta (SUPERSEDED by the 0.9.2 block above; kept for history)
- **main = `3c598ad`**: P1a + P1b-1 + PR-A + PR-B + PR-C + D1 + D2a + D2b + **D2c (PR #46, MERGED)**.
- **D3 (idle auto-lock)**: DONE, PUSHED, **PR #48 OPEN** (branch `feat/0.9.1-vault-d3-autolock` @ `13d59cb`, base main). https://github.com/jackofall1232/zitrone/pull/48
  Two-reviewer coverage: **Grok DONE** (local, `d3-review-grok.md` + `d3-adjudication.md`): verdict NO
  new writer under all 3 timings; 0 Critical/High/Med; 3 Low (TOCTOU by-design residual, register()
  double-add latent, test gaps) — only the test-gap fix (#3, add negative-timeout + fire-time `(false,true)`
  asserts) is actionable, HELD to batch w/ Codex. **Codex PENDING** (USER dispatches from PR — reviews from
  a PR, not a local diff). PR body carries the focused scope. CI runs on the PR. NOT merged; no version bump.
- App version unchanged: **vc16 / 0.9.0-beta** (no bump yet).
- **Release gate for 0.9.1-beta cut + website flip** = PR-D (D2c+D3+D4+D5) + PR-F. D2c✅, D4✅(absorbed
  into D2c). **Remaining: D3 (review→merge) → D5 → PR-F.**

## Standing constraints (unchanged all session)
- No merge over any unresolved CONFIRMED finding, whatever severity.
- No version bump / push / merge without explicit user approval.
- Durable-signal changes: build the WRITER/READER invariant table FIRST (round-12 lesson).
- Fixes get an INDEPENDENT review before merge — for D2c that was TWO blind reviewers (Codex+Grok);
  D3 is structurally lower-risk (never writes delete/token state) so ONE focused pass is proportionate.
- Do NOT self-dispatch the "final" review as a self-re-read. Reviewer CLIs: `/root/.local/bin/codex`,
  `/root/.grok/bin/grok`. Prior artifacts: `/root/l00prite/d2c-r{13..16}-review-{codex,grok}.md` + adjudications.

## D2c review arc (the hard part — 16 rounds; every review round found a real defect the fix missed)
- 12 internal fix rounds, then 5 independent two-blind-reviewer rounds (r13–r16). Pattern: each round the
  two reviewers caught DIFFERENT things; a single reviewer would have passed a real defect every time.
- r13: F1(P1 auth-cleared-before-durable-confirm / broken roll-forward), F2(P2 create() trusted delete() bool),
  F3(P3 clearDeleteIntent dirSync).
- r14: two-marker split (`vault.delete-intent` vs `vault.delete-confirmed`); confirmed R14-1(P2 onSessionRevoked
  clears tokens in the intent→confirmed window) + File.exists indeterminate.
- r15: guard `deleteInFlight` — but its lifetime was coroutine/RAM, invariant scope is the DURABLE marker.
- r16: guard now `deleteInFlight || intentMarkerPresent()` (durable, spans not-confirmed exits + restart);
  r16b fixed `hasDeleteIntentMarker` to `!Files.notExists` (fail-closed on indeterminate stat).
- Merged after CI green (Android/Desktop/Go/Security-scan/TS/Vercel all pass). Residuals remaining are all
  documented FAIL-SAFE (retention / TOCTOU microsecond / unhealthy-FS): NO data-loss, disclosure,
  unauthorized-destruction, or re-registration. onSessionRevoked check/clear TOCTOU (R16-R1) is the last,
  P3, both reviewers non-blocking.

## Key delete-state facts (for anyone touching lock/teardown/delete)
- Token-clear writers (enumerated, verified): `onSessionRevoked`→clearTokens (GUARDED by
  deleteInFlight||intentMarkerPresent), `deleteSession` (DEAD, no caller), account-delete finishUi
  `signalStore.wipe`, `wipe`/`destroy` (after confirmed). `deleteAccount`/`onAuthExpired` do NOT clear.
- `clearTokens` clears tokens ONLY, never `accountId` → no silent re-registration.
- Delete-marker writers: markDeleteIntent / markServerDeleteConfirmed / destroy / clearBothMarkersDurably /
  create / clearDeleteIntent — none in the lock() path.
- **lock() teardown = coordinator.stop() [no token clear, no marker write] + runtime.close() [VaultSession.close
  doFlush RESEALS current state → RETAINS auth on disk; then RAM wipe] + scope.cancel + publish(null).**
  So a plain LOCK never clears tokens or writes markers — it reseals (retains) auth and wipes RAM.

## D3 design (device-level auto-lock) — decisions
- USER DECIDED device-level (not vault-scoped): avoids a VaultState storage-format change + settings-seam
  scope growth; cleaner deniability (reveals nothing about vault count / active slot). Per-vault timeout
  differentiation deferred as its own future feature.
- `autoLockTimeoutSeconds` on SettingsRepository (rides batch load — no new startup decrypt), via DeviceSettings.
  Options 0(immediate)/60/300/900, default 300.
- `VaultLockManager`: ProcessLifecycleOwner observer + coroutine timer; REUSES UnlockController.lock() (no 2nd
  teardown); onStop schedules/immediate-fires, onStart cancels, fire re-checks. Pure decision extracted
  (`autoLockOnBackground`/`shouldAutoLockAtFireTime`) → 5 host tests.
- Skips firing during `isTerminalWipe()` (new UnlockController reader) so a bg timer never races a delete teardown.
- New dep `androidx.lifecycle:lifecycle-process`; ProcessLifecycleInitializer VERIFIED to survive R8
  (assembleRelease green; merged release manifest + release dex retain it — the catalog warns lifecycle can be
  stripped in minified builds, so this was checked).
- 413 tests green; assembleDebug + assembleRelease green.

## Remaining to release (after D3 merges)
- **D5**: scope must be RE-DERIVED (the PRD-PLAN.md scratchpad did NOT persist). Likely final integration /
  BootDiagnostics-per-session; confirm with the user before building.
- **PR-F (docs/release notes)** MUST disclose: "fresh install required" (PR-E/migration was dropped —
  existing beta installs won't upgrade); the storage-format-stability decision (commit vs disclose
  wipe-on-breaking-change); the contact-deletion-permanence guarantee wording.
- Standing hygiene owed before external testers (Claude memories): semgrep SAST broken + release-apk.yml
  shell-injection; website over-claims an undeployed web client.
- Benchmark gate already PASSED (Revvl, flush p95 ~8ms).
- THEN: version bump, signed 0.9.1-beta APK (verify cert 6c7f92a7…892753), GH release, Vercel apex flip.
- Phase order after gate: PR-F → P2 (2nd slot + teardown-on-switch) → P3 (setup wizard + destruction).

## Pucker Burn advisory round + product decisions (2026-07-24)
- **Advisory:** 4 mutually-blind advisors (Claude un-anchored-first, Codex, Grok — both repo-reading,
  Moonshot/kimi-k3 from cited facts; its first run returned a placeholder, background re-run landed).
  Unanimous convergence on all five questions. Synthesis: `/root/l00prite/pucker-burn-synthesis.md`.
- **Source-verified key finding (Codex+Grok, re-verified against source this session):** `destroy()`
  (`VaultImageStore.kt:1056`) writes `vault.delete-confirmed` REQUIRED-DURABLE *before* the unlinks and
  throws with vault files untouched if the marker can't fsync. Calling it from burn is broken 3 ways:
  asserts a false "server confirmed gone"; crash mid-burn → `Route.DeleteIncomplete` (discoverable tell +
  could provoke a later real network delete); fail-OPEN (can throw before destroying anything). Burn spec
  MUST decompose: marker-free, fail-closed, keys-first `obliterate` primitive shared by destroy() and burn.
- **Moonshot-only ship-blocker:** wiring hazard — `UnlockOrAdd.Burn` is returned by the general-purpose
  `attemptUnlockOrAdd`, which is also the add-slot collision path; naive wiring makes 2nd-vault creation
  with an unlucky candidate a self-DoS wipe. Wipe wires ONLY to lock-screen unlock dispatch.
- **USER DECISION 1 — settings entry NEVER DISAPPEARS** (overturns the locked "disappears once set").
  Rationale: the disappearing entry is an armed-state oracle, and hiding it forces a persistent armed-flag
  (prefs/DB/header) that is itself the forbidden discoverable artifact and rides Auto-Backup; also,
  enforcing "unchangeable once set" has the same oracle problem. Consequence accepted: re-running setup
  re-seals slot 0 — permanence is reframed "unrecoverable and unknowable", not "unrewritable" (side
  benefit: cures the untestable-credential footgun; unsure users just set it again).
- **USER DECISION 2 — post-burn = VISIBLE RESET** (user ruling AGAINST own advisory finding, deliberately;
  decoy deferred not rejected). (i) decoy needs per-vault destruction (doesn't exist, assessed harder than
  creation) + surviving-decoy-slot concept + fresh deniability analysis inside an unshipped feature = the
  D2c bundling pattern; (ii) RECORDED UNEXAMINED FAILURE MODE for future decoy work: an unprepared/
  empty/synthetic decoy under observation is WORSE than a visible reset — reveals the feature and its
  invocation; needs dedicated design, not scope-expansion. HONEST LIMIT to document plainly in
  SECURITY_MODEL.md: burn protects the DATA, not the FACT that data existed — a coercer watching the
  screen sees onboarding and knows a wipe happened. SEQUENCING: visible reset does not foreclose decoy;
  it layers on later with its own phase/review; burn credential mechanism unchanged.
- **USER DECISION 3 — wipe DoD = BYTE-FOR-BYTE GATE.** Instrumented test diffs app-local state (files,
  prefs, DBs, Keystore aliases, notification channels, WorkManager jobs) post-burn vs post-fresh-install:
  zero delta. Not a checklist — only the mechanical gate survives the author no longer thinking about it
  (three recorded plan→code staleness incidents: D5 scratchpad, PR-2 wiring drift, Unit-2 docs-vs-source);
  burn is the one place where incompleteness is silent AND catastrophic (no normal-use feedback loop).
  REQUIREMENT: genuinely-unclearable OS residuals (install time, Auto-Backup state, account-manager,
  media, notification history) must be EXPLICITLY enumerated + asserted known-and-accepted with a stated
  per-exclusion reason IN THE TEST (an exclusion list that grows without scrutiny is a checklist wearing
  a test's clothes), mirrored in SECURITY_MODEL.md.
- **Next:** Pucker Burn SPEC (setup + wipe; Q1 local-only / Q2 decomposed primitive / Q3 no format change
  resolved per advisory; surface any remaining calls). SPEC ONLY — stop before implementation; same
  review gate as every unit.

## Pucker Burn SPEC finalized (2026-07-24) — pending user review, stop before impl
- Spec: `/root/l00prite/pucker-burn-spec.md`. Two sibling units, sequenced W→S.
- **USER DECISION 4 — gate harness = Robolectric in `src/test`** (not androidTest connected — emulator
  availability in CI unconfirmed; a gate that can't run in CI isn't a gate; not hybrid — its manual half
  covers exactly the artifacts most likely forgotten). Shadow-fidelity gaps accepted ONLY as explicit
  in-test exclusions with a stated reason + a matching SECURITY_MODEL limitation line (no claiming
  fresh-install-indistinguishability for anything the test doesn't verify).
- **USER DECISION 5 — sequencing = Unit W (wipe) FIRST, then Unit S (setup).** Slot 0 unarmed = Burn
  structurally unreachable, so keys-first obliterate() lands + clears full review while nothing can
  trigger it; setup flips reachability only after the mechanism is proven. Setup-first rejected: arming
  over a stub = a duress credential that silently does nothing (worst state the feature can produce).
- **USER BINDING CAVEAT on the obliterate marker-clear override (backs the Codex override):** marker clear
  MUST be STRICTLY AFTER the DEK+image unlinks are proven durable, never before (else PR-1 B1's
  markers-say-gone-over-live-image state is reproduced inside burn). Because the image is proven absent
  first, markers are orphaned by definition (same precondition that makes create()'s clear safe). Boot
  reconciliation MUST handle a crash BETWEEN unlink and marker-clear: image gone + markers present →
  complete the clear, land on fresh onboarding; confirm an image-absent state can never route to
  DeleteIncomplete auto-destroy. Both folded into spec §3.2/§3.4/§8.
- **HONEST EQUIVALENCE FLAG (recorded, review item):** the destroy() refactor preserves EXTERNAL behavior
  but changes ONE internal detail — unlink order bin-then-dek → dek-then-bin (keys-first). Safe for
  destroy() (confirmed-marker-first makes re-destroy idempotent regardless of order) but NOT a
  strict-identity refactor; must be verified against source in review, not assumed. Fallback if rejected:
  a `keysFirst` param on obliterate() (destroy passes false, burn true).
- **USER DECISION 6 — Unit W gets full D2c-level scrutiny** (two blind reviewers to clean convergence,
  Moonshot R6 third lens on non-convergence, adjudicate vs source); destroy()-equivalence is its own named
  review item. Unit S lower-risk (one focused pass may suffice). ≤5 agents per budget discipline.
- **NEXT:** user reviews the finalized spec. No implementation until they approve. Then Unit W first.

## PROCESS DECISION (2026-07-25): PR-attached reviewer is a HARD PRE-MERGE GATE
- **SUPERSEDED — the original "out-of-band advisory" handling recorded earlier this day is NO LONGER
  STANDING.** It is replaced by the gate below (user decision, same day, after the PR #59 triage
  results came in). Only ONE handling stands: this one.
- **THE GATE.** After a unit reaches clean convergence AND CI is green, the PR may NOT be merged until
  the PR-attached reviewer (Gemini code-assist bot, or whatever reviewer is attached) has commented AND
  its findings have been triaged. This is ADDITIONAL to `merge_confirmation_required` — explicit human
  merge permission is still required after all of it.
- **WHY (division of labour — the two lenses are NOT substitutes).** Paired-blind reviewers are pointed
  at a SPECIFIC DELTA with binding focus items and go DEEP on the written code (invariants, edge cases,
  spec conformance); they do not see the repo as a whole and are not asked to. The PR-attached reviewer
  sees the diff IN THE CONTEXT OF THE WHOLE REPO and is positioned to catch what is only wrong RELATIVE
  TO SURROUNDING CODE — a rule whose siblings do it differently, a pattern inconsistent with the tree,
  content internally coherent but wrong against broader reality. PR #59 is the proof case: the vendored
  rules were self-consistent (nothing in the delta looked off) but wrong about how modern Go is written
  (`any` vs `interface{}`) and how regex classes parse (`[a-zA-Z0-10]`). The loop structurally could
  not see it.
- **EXPECTED TRAJECTORY:** as the loop keeps deltas clean, the PR reviewer should say progressively LESS
  about the change itself and proportionally MORE that is repo-context. **A quiet PR reviewer is the
  loop working, not the gate being useless.** Do not let a future session rule it redundant.
- **TRIAGE STANDARD — verify, never accept.** Every claim verified independently against source with
  EXECUTABLE proof where possible (fixtures/reproduction), not by reasoning. Classify each: CONFIRMED
  REAL / FALSE / PARTIAL-WITH-WRONG-MECHANISM. Where the reviewer is right about a defect but wrong
  about its cause or DIRECTION, say so explicitly — the mechanism determines the fix. Precedent: on
  PR #59, 2 of 5 claims were FALSE and a third had the right conclusion via the wrong mechanism.
  **"Wait for the reviewer" must never become "fix whatever it says."** Confirmed findings BLOCK merge
  if they affect the unit under review; confirmed findings outside its scope become a follow-up and do
  NOT block.
- **TIMEOUT:** if the PR-attached reviewer has not commented within **60 minutes** of PR opening,
  proceed to merge on the existing gates and triage its comment when it arrives; RECORD the timeout
  event in this ledger. The gate must not become an indefinite block on a service we do not control.
- Canonical text lives in `l00prite/.l00prite/prompts/security-review-loop.md` (single copy in this
  target; the byte-mirror discipline covers the six canonical l00prite loop prompts, which this
  zitrone-specific prompt is not one of). Validator 606 PASS / 0 FAIL; doctor HEALTHY.
- Triage discipline unchanged and reaffirmed: verify EVERY claim against source independently, accept
  or reject each on its own evidence, never accept-or-dismiss the batch. Recorded precedent of Gemini
  noise (D2c rounds 10-12: hallucinated compile errors; a java.util.Base64 false positive raised five
  times) is a reason to VERIFY, not to dismiss — the PR-59 batch contained at least one mechanically
  correct finding.

## Unit W round 2 — CALIBRATION EVIDENCE for the wipe-first sequencing decision (2026-07-25)
- **The round-2 HIGH was a NEW WRITER COLLIDING WITH ITSELF.** `beginTerminalWipe()` is set-true, not an
  exclusive claim, so two burn workers could co-own the gate; whichever finished first called
  `endTerminalWipe()` and reopened session creation while the other was still inside
  `obliterateForBurn()`. A successor vault created in that window would be destroyed by the straggler —
  a self-inflicted total wipe of a brand-new vault. Reachable via Activity recreation (the
  composition-local `unlocking` guard resets; `attemptPassphrase`'s single-flight is already released
  before `onBurn` runs). Both blind reviewers found it independently. Fixed with
  `tryBeginTerminalWipe()` — only the winner works, only the winner releases — plus 4 tests including a
  16-thread contention proof that exactly one claimant wins.
- **WHY THIS IS CALIBRATION EVIDENCE (user, record it):** the path is reachable ONLY once Unit S arms
  slot 0. This is precisely what the **wipe-first (W→S) sequencing decision** was chosen to contain: the
  dangerous durable-state defect LANDED and GOT CAUGHT while nothing in production could trigger it.
  Had setup shipped first, an armed slot 0 would have made this live. **The sequencing paid for itself.**
  Cite this when making future sequencing calls — mechanism-before-trigger is not merely tidy ordering,
  it buys a full review cycle of exposure-free defect discovery on the destructive path.
- **VACUOUS TEST — its own lesson, not just a fix.** `cache clear reports failure when content survives`
  asserted SUCCESS after an ordinary successful delete; it never produced the failure it was named for.
  **A test named for a failure case that asserts success is WORSE than no test** — it reads as coverage
  in the file listing and in review, so the gap it names looks closed. Both reviewers flagged it
  independently. Renamed to what it actually proves, with the untestable shape (a genuinely undeletable
  file, which Robolectric cannot force) stated outright. Suite-wide sweep for the pattern queued in
  `todos.md`, unsequenced.
- **Round-2 status:** Codex + Grok converged on all findings (1 HIGH, 2 MEDIUM, 1 LOW, 1 INFO). All
  fixed. Both independently RE-AFFIRMED destroy() equivalence (keys-first safe; `keysFirst` param
  unnecessary) and both agreed with scoping the inherited `File.exists()` verify out of this unit.
  Per standing discipline the round-2 fix delta is a NEW delta requiring its own round-3 paired-blind
  pass — fixes are guilty until independently proven otherwise. NOT converged; NOT ready to merge.
