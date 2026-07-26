# Zitrone — SPEC: CI pipeline security (release-apk.yml shell-injection + SAST breakage)

Status: SPEC ONLY — awaiting maintainer review before any code. No version bump. Base: `main`
(`e32f0aa`). Pipeline/build-config work (repo `.github/workflows/`) — CX33/build-config domain, NOT
CX23 prod; no box is touched, only repo workflow files. Sequenced AHEAD of Pucker Burn: the injection
is a live, exploitable hole in the pipeline that builds+signs release APKs (a compromised signed build
is worse than any unbuilt feature); the SAST breakage is fixed in the same pass because it is *why* the
injection went uncaught (semgrep silently green).

## Part 1 — release-apk.yml shell-injection (close EVERY input→shell path)

### The vulnerability class
GitHub Actions substitutes `${{ … }}` into a `run:` script as TEXT **before** the shell parses it. Any
`${{ … }}` whose value an attacker can influence, interpolated into a `run:` block, is arbitrary shell
execution in that job. This job runs `environment: android-release` with the **release signing key** in
secrets — so injection = signing-key exfiltration / tampered signed APK. Highest-value target in the repo.

### Attacker-controlled root: the release TAG
`on: push: tags: ["v*"]` (a pushed tag name — `github.ref_name`) and `workflow_dispatch inputs.tag`
(free-form dispatch input) are both attacker-influenceable (anyone who can push a `v*` tag or dispatch
the workflow). A tag/input like `` v1.0`curl …|sh` `` or `v1.0"; <cmd>; echo "` injects. That value then
FLOWS into derived step outputs (`steps.meta.outputs.tag`, and `steps.stage.outputs.apk = zitrone-$TAG.apk`)
which are themselves interpolated into later `run:` blocks — so the fix must cover the DERIVED paths too,
not only the origin.

### Complete enumeration of `${{ }}`-into-`run:` (every one to neutralize)
| Line | Expression in a `run:` block | Source | Attacker-controlled? |
|---|---|---|---|
| 61 | `TAG="${{ github.event.inputs.tag \|\| github.ref_name }}"` | dispatch input / tag name | **YES — primary** |
| 80 | `TAG="${{ steps.meta.outputs.tag }}"` | derived from 61 | **YES** |
| 118 | `TAG="${{ steps.meta.outputs.tag }}"` | derived | **YES** |
| 120 | `if [ "${{ steps.signing.outputs.signed }}" …` | workflow-set "true"/"false" | no (neutralize anyway) |
| 139 | `APK="…/${{ steps.stage.outputs.apk }}"` | derived (`zitrone-$TAG.apk`) | **YES** |
| 165 | `TAG="${{ steps.meta.outputs.tag }}"` | derived | **YES** |
| 170 | `…${{ steps.stage.outputs.sha256 }}…` | sha256sum output (hex) | no (neutralize anyway) |
| 180 | `TAG="${{ steps.meta.outputs.tag }}"` | derived | **YES** |
| 186–187 | `…${{ steps.stage.outputs.sha256 }}… ${{ steps.verify.outputs.cert_sha256 }}…` | hex / apksigner output | no/semi (neutralize) |
| 192 | `--repo "${{ github.repository }}"` | repo slug | no (neutralize anyway) |
| 210 | `TAG="${{ steps.meta.outputs.tag }}"` | derived | **YES** |

NOT shell (action `with:` inputs — note but not the same class, leave unless a reviewer shows a path):
L56 `ref:` (checkout input — checkout validates the ref), L203 `name:` (artifact name), L204 `path:`.

### The fix (uniform — every row above, not just the flagged one)
1. **Env-var indirection for EVERY `${{ }}` that reaches a `run:` block.** Map the expression to an
   `env:` entry on the step and reference the shell `$VAR` (quoted) in the script — env values are passed
   to the process, never string-substituted into the script, so no metacharacter can execute. Apply to
   ALL rows (attacker-controlled and workflow-controlled alike — "close every path", and it removes the
   need to reason per-value about controllability).
2. **Always quote** `"$VAR"` at every use (filenames, `gh release create` args, `case`/`[` tests) so the
   VALUE is used literally and safely even when it contains spaces/metacharacters.
3. **Derived outputs:** the meta step should compute `tag` from env-var'd inputs (not `${{ }}` in the
   script); downstream steps read `steps.meta.outputs.tag` / `steps.stage.outputs.apk` via `env:` too.
4. **Defense-in-depth (recommended): validate the tag shape early.** Right after resolving `TAG`, reject
   anything not matching a strict release-tag pattern (e.g. `^v[0-9]+\.[0-9]+\.[0-9]+(-beta)?$`) before it
   is used anywhere — a belt on top of the env-var fix (the existing versionName assertion at L76 runs too
   late to protect L61). Keep it consistent with the accepted tag forms (`vX.Y.Z`, `vX.Y.Z-beta`).
5. Preserve ALL existing behavior (signed/unsigned branches, cert-continuity pin, release notes, website
   pointer, artifact upload). Pure hardening — no functional change to what the workflow produces.

## Part 2 — SAST breakage (semgrep must gate, and fail on its own crash)

### The defect
`ci.yml` `security` job → `Semgrep` step: `uses: semgrep/semgrep-action@v1` with `config: auto`. This
deprecated action does not reliably fail the build on findings and **exits 0 on its own crash / a config
fetch failure**, so the "Security scanning" job has been green because of the *Trivy* step next to it —
Semgrep has been a silent no-op. Static analysis has effectively not run.

### The fix
Replace the swallowing action with a **pinned semgrep CLI invocation in a `run:` step** so that BOTH a
real finding AND any semgrep error/crash produce a non-zero exit that gates the job:
- Install a **pinned** semgrep version (pin the pip package version or use a pinned `semgrep/semgrep:<tag>`
  container — not a floating `@v1`/`latest`), so the scanner can't silently change or regress.
- Invoke `semgrep scan --config <ruleset> --error --strict` (or `semgrep ci` with equivalent flags):
  - `--error` → exit non-zero when there ARE findings (gates on a real result).
  - `--strict` → treat rule/parse/config problems as errors (non-zero), so a broken/empty ruleset can't
    pass as "0 findings."
  - A `run:` step fails on ANY non-zero exit, so a semgrep **crash** (segfault, OOM, network/config
    failure) also fails the job — the exact swallow the old action allowed.
- **Config that works without a login token:** `config: auto` needed semgrep.dev/registry behavior the old
  action degraded silently. Use a config that is deterministic in CI — a pinned registry ruleset
  (`p/default` or a curated set) fetched by the CLI (a fetch failure is now a hard error, not a swallow),
  or vendored local rules under `.semgrep/` committed to the repo (fully offline, most reproducible).
  OQ-S1: registry ruleset vs vendored local rules — recommend **vendored/pinned** for reproducibility +
  offline determinism; decide with the maintainer.
- Keep Trivy as-is (it already gates correctly).

## Part 3 — Proof the SAST fix actually gates (requirement 3)
A fix that is "assumed green" is the same failure we are removing. Two-part proof:
1. **Local, during implementation:** run the exact fixed semgrep invocation against a PLANTED finding
   (a file with a pattern the chosen ruleset flags — e.g. a hardcoded secret / dangerous eval / the
   canonical semgrep test rule) and confirm it exits NON-ZERO; and run it against clean tree → exit 0.
   Also simulate a crash/broken-config → confirm non-zero. Capture the exit codes as evidence.
2. **Throwaway-branch CI proof:** push a throwaway branch containing the fix PLUS a deliberately planted
   finding, and confirm the `Security scanning` job goes RED on that branch; then confirm the fix branch
   WITHOUT the planted finding is green. This proves the scanner runs + gates end-to-end in CI, not just
   locally. NOTE: this needs a branch push — flag for the maintainer's go-ahead (per "no push without
   call"); the local proof (1) is done first and unblocked.

## Review focus (paired-blind loop — DIFFERENT from app-code units)
The question is **input coverage and gating**, not invariants:
- **Injection completeness (primary risk = incomplete fix):** does the fix close the injection under
  EVERY input path and VARIANT — every `${{ }}`-into-`run:` from the table, including the derived
  `steps.*.outputs.*` that carry the tag — or does a variant remain reachable? An injection fix that
  closes the demonstrated line but leaves a sibling reachable is the failure mode; treat it as primary.
- **SAST gating:** does the new semgrep invocation cause a FUTURE injection/finding to FAIL the build
  (not pass silently), including on semgrep's own crash/error? Is the version pinned so it can't regress?
- Confirm no behavior regression to the release build/sign/publish paths.

## Scope / sequencing / constraints
- IN: `.github/workflows/release-apk.yml` (injection hardening), `.github/workflows/ci.yml` (SAST gating),
  any vendored `.semgrep/` rules + the proof. OUT: app code, Pucker Burn, any prod/CX23 action.
- Spec → maintainer review → implement → paired-blind loop (Codex + Grok; Moonshot round-6-only on
  non-convergence; hard-stop + HIL after round 6) → ready-to-merge. No push/merge without per-action
  approval. No version bump.

## OPEN QUESTIONS
- **OQ-S1:** semgrep config — pinned registry ruleset (`p/…`) vs vendored local `.semgrep/` rules?
  (Recommend vendored/pinned for offline reproducibility.)
- **OQ-S2:** the throwaway-branch CI proof needs a branch push — do it as part of this unit (with your
  go-ahead on the push), or is the local exit-code proof sufficient for the spec's "proof" bar with the
  CI proof run at merge time?
- **OQ-S3:** add the early strict tag-format validation (Part 1 #4) as belt-and-suspenders, or rely on
  env-var indirection alone? (Recommend adding it.)
