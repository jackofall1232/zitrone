You are an INDEPENDENT ADVERSARIAL CI/PIPELINE-SECURITY REVIEWER. Report findings only, verified against shipped code. CONFIRM round for the SAST run-interpolation gate. Report ONLY a real defect / a reachable injection variant the gate misses (blocking) — not style. PRIMARY RISK = an incomplete gate (a `${{ }}`-into-`run:` variant that passes silently).

## Delta to review
`262268c..2c339db` on branch `feat/ci-security-hardening` (/root/zitrone). `git diff 262268c..2c339db`. It changes ONLY `.semgrep/local/no-run-block-interpolation.yaml`. IMPORTANT: review the COMMITTED HEAD state (`git show HEAD:.semgrep/local/no-run-block-interpolation.yaml`), not any transient worktree.

## What round 2 found + this delta claims
Both reviewers found the local rule's `${{ ... }}` generic pattern missed a `${{ … }}` expression split across many blank lines inside a `run: |` block (semgrep's generic `...` ellipsis is bounded, default ~10 newlines). FIX: `options.generic_ellipsis_max_span: 10000` on the rule, so the `${{ ... }}` match spans up to 10000 lines — a single interpolation spanning that many lines is absurd/obvious in review.

## Verify (binding)
1. **Multiline-span bypass CLOSED:** does the HEAD rule now flag a `${{ … }}` split across, say, 5 / 40 / 200 blank lines inside a `run: |` block? At what line-span (if any) does it still evade — is 10000 a practical ceiling no reviewable workflow reaches, or a real remaining gap you can demonstrate under it?
2. **Gate completeness (the crux):** enumerate the `${{ }}`-into-`run:` shapes and confirm coverage at HEAD — one-line `run:`, `run: |`, `run: >`, quoted scalars, no-space `${{steps…}}`, direct `github.*` (vendored rule) AND derived `steps.*.outputs.*` (local rule), composite `action.yml`. Any variant that still passes the combined gate?
3. **No false-positive regression:** the fixed tree must stay 0 findings — the rule must NOT flag `${{ }}` in `env:`/`with:`/`if:`/`ref:` (only `run:`). Confirm the committed rule is the WORKING `${{ ... }}` + span version (not a broken/empty matcher), rules load (`--config .semgrep` picks up local/), and `--error/--strict/run:`-non-zero gating is intact.
4. **release-apk.yml** still fully closed (zero `${{ }}` in run, tag validated first, `::error::` sanitized), digest-pin intact — nothing regressed by this delta.

## Output
For 1-4: CONFIRMED-ACCURATE (evidence) or a real finding (SEVERITY, FILE+line, the exact bypass). One-line verdict (CLEAN or the blocking finding). Report ONLY.
