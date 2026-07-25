I'll adversarially verify the delta and the current CI/workflow config for input coverage and gating completeness.Next I'll probe the local Semgrep rule against injection variants and scan the full workflow set for residual gaps.Running adversarial fixture tests against the local rule for injection coverage gaps.0 findings on chomp/`|2` fixtures looks like a real miss — re-testing carefully.The local rule file size changed mid-review — inspecting it and re-validating against the tree.Uncommitted rule change may have broken matching — comparing committed vs working-tree behavior.## Round-1 status

| Finding | Status | Evidence |
|---|---|---|
| **HIGH** SAST missed `${{ steps.*.outputs.* }}` → `run:` | **CLOSED @ `262268c`** / **REOPENED in worktree** | See F1, F2 |
| **LOW** reject-path `::error::` echoed raw `$TAG` | **CLOSED** | See F3 |
| **Residual** semgrep tag-pin not digest-pin | **CLOSED** | See F4 |

---

## Findings

### F1 — HIGH — incomplete-fix / gate regression (worktree ≠ `262268c`)
**FILE:** `.semgrep/local/no-run-block-interpolation.yaml` (uncommitted vs `262268c`)  
**MECHANISM:** Worktree replaces working `metavariable-pattern: pattern: ${{ ... }}` with `metavariable-regex: regex: \$\{\{`. That regex form yields **0 matches** on real injection fixtures.  
**SCENARIO:** Re-introduce:
```yaml
- run: echo "${{ steps.meta.outputs.tag }}"
```
Under **worktree** ruleset: only vendored `run-shell-injection` fires on enumerated `github.event.*`; **derived-output variant is silent** (1 finding total, the direct one only). Under **`262268c`**: local + vendored catch both (3 findings).  
**Impact:** The exact round-1 HIGH gap is open again in the current tree if this change is kept.

### F2 — MEDIUM — incomplete gate (committed local rule, residual miss)
**FILE:** `.semgrep/local/no-run-block-interpolation.yaml:39-44` (`262268c`)  
**MECHANISM:** `metavariable-pattern` + generic `${{ ... }}` uses a bounded ellipsis.  
**SCENARIO:** Blank-pad inside the expression (GHA still evaluates it):
```yaml
run: |
  echo "${{




    steps.meta.outputs.tag
  }}"
```
Measured: **caught** for 0–3 blank lines each side of the body; **missed** for **≥5** blank lines each side (`findings=0`). Common one-line / short multi-line forms are caught.  
**Coverage that does work @ `262268c`:** scalar `run:`, `run: |`, `run: >`, `|-`/quoted scalars, composite `action.yml`, `matrix`/`needs`/`secrets`/`format`/`toJSON`, no-space `${{steps…}}`, far-down script lines; **does not** flag `env:`/`with:`/`if:`/`ref:`.

### F3 — (none new) — `::error::` sanitize holds
**FILE:** `.github/workflows/release-apk.yml:76-79`  
**MECHANISM:** `printf '%s' "$TAG" | tr -d '\r\n' | cut -c1-64` → single-line `::error::…'$SAFE_TAG'…`.  
**Verify:** CR/LF-injected `TAG` collapses to one stdout line; no second `::…::` line. Empty/long/`::` mid-string inputs stay one line.  
**Other `::` / `$GITHUB_OUTPUT` paths:** reject path does not write `GITHUB_OUTPUT`. Downstream `::error::` / `tag=$TAG` only after `^v[0-9]+\.[0-9]+\.[0-9]+(-beta)?$`. No new raw-untrusted → `::` / `GITHUB_OUTPUT`/`GITHUB_ENV` on this delta.

### F4 — (none) — digest pin OK
**FILE:** `.github/workflows/ci.yml:143-145`  
**MECHANISM:** `semgrep/semgrep:1.90.0@sha256:7b625711ba9b6d1a543e308967b18c01b59932490a5536a06422666474bf6ee4` — 64-hex digest, pull resolves, image ID matches, `semgrep --version` / full gate runs.  
**Gating:** `--config /src/.semgrep --error --strict` intact; `--validate` → 22 rules (includes `local/`); `run:` non-zero still fails the job. Clean tree @ committed ruleset: **0 findings**.

### F5 — INFO — out-of-policy residual (not run-block)
**Not covered by local rule (by design):** `${{ }}` in `working-directory:`, `shell:`, or `actions/github-script` `with.script` (latter has separate vendored rule). No current workflow uses these for untrusted tag flow. `link-check.yml`: no untrusted → `run:`. No new secret-log path from this delta.

### F6 — (closed) — release-apk injection surface
**FILE:** `.github/workflows/release-apk.yml`  
Zero `${{ }}` in any `run:` body; tag via `env:` only; validate-before-output; no new run-interpolation in the delta.

---

## Round-1 reopened?

| | Committed `262268c` | Current worktree |
|---|---|---|
| Derived-output gate | Closed (common shapes) | **REOPENED** (local rule dead) |
| Direct `github.event.*` gate | Closed (vendored + local) | Still closed (vendored only) |
| `::error::` sanitize | Closed | Closed |
| Digest pin | Closed | Closed |

---

## Verdict

**NOT CLEAN — F1 (worktree neuters local rule; derived-output re-injection silent) is blocking; residual F2 (blank-padded `${{` split ≥5 lines) incomplete-gate on committed rule.**
