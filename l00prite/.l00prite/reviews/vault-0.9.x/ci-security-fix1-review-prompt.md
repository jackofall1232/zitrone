You are an INDEPENDENT ADVERSARIAL CI/PIPELINE-SECURITY REVIEWER. Report findings only. This is CI/build-config; the question is INPUT COVERAGE and GATING, not code invariants. PRIMARY RISK = incomplete-fix (a reachable injection variant, or a gate that misses a variant). A fix can introduce a new defect. SECOND (fix) round.

## Delta to review
`e61b96f..262268c` on branch `feat/ci-security-hardening` (/root/zitrone). `git diff e61b96f..262268c`. Also read the FULL current `.github/workflows/release-apk.yml`, `.github/workflows/ci.yml`, and `.semgrep/local/no-run-block-interpolation.yaml`.

## The round-1 findings this delta claims to close (verify EACH, and NONE reopened)
- **HIGH (both reviewers): SAST gate missed `${{ steps.*.outputs.* }}` → `run:`** (the upstream `run-shell-injection` rule matches only enumerated `github.event.*`). FIX: added a LOCAL rule `.semgrep/local/no-run-block-interpolation.yaml` that flags ANY `${{ … }}` interpolated into a `run:` block (direct AND derived). Verify: does the local rule actually flag a derived-output injection (`run: echo "${{ steps.x.outputs.y }}"`) AND direct (`${{ github.event.* }}`) AND still NOT flag `${{ }}` in `env:`/`with:`/`if:`/`ref:` (so the fixed tree stays clean)? Is there any `run:`-interpolation shape it MISSES — a `run:` on one line vs `run: |` block, a `run:` inside a matrix/composite, `${{ }}` split across lines, or a step whose `run:` the `pattern-inside` scoping doesn't reach? Confirm the combined ruleset (vendored + local) fails a re-introduced injection of EITHER variant and is 0-findings on the current tree.
- **LOW (Grok): reject-path `::error::` echoed the raw invalid `$TAG`** → a newline could inject `::workflow-command::` lines. FIX: sanitize (`tr -d '\r\n' | cut -c1-64`) before echoing. Verify the sanitization actually neutralizes the `::`-injection (no CR/LF reaches the `::error::` line) and doesn't itself break on odd input. Are there OTHER places raw untrusted input reaches a `::…::` workflow command or `$GITHUB_OUTPUT`/`$GITHUB_ENV` on any path?
- **Residual (Grok): semgrep image tag-pinned not digest-pinned.** FIX: digest-pinned `semgrep/semgrep:1.90.0@sha256:7b62…`. Verify the digest pin is well-formed and the image still runs the gate.

## Verify (binding)
1. **Gate completeness:** with the local rule added, is there ANY interpolation-into-`run:` variant that still passes the gate? Enumerate the run-block shapes and confirm coverage. This is the crux — an incomplete gate means a future injection passes silently.
2. **Injection still fully closed in release-apk.yml** (the actual workflow): zero `${{ }}` in any run line (re-confirm), tag validated first before any derivation, `::error::` sanitized, no new interpolation introduced by this delta.
3. **Gating intact:** `--error`/`--strict`/`run:`-non-zero still gate; digest-pin didn't break invocation; rules still load (`--config /src/.semgrep` picks up `local/`).
4. **No new defect / regression** from the sanitize or digest-pin.
5. Anything else reachable: link-check.yml, the semgrep step itself, secrets in logs.

## Output
Structured findings (SEVERITY, FILE+line, MECHANISM, concrete SCENARIO). State CLOSED/NOT-CLOSED for each round-1 finding. One-line verdict (CLEAN or the blocking finding). Report ONLY.
