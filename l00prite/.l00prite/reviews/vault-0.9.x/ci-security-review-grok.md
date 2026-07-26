I'll independently review the CI security delta: enumerate every `${{ }}` path, validate tag gating, and verify SAST actually fails closed.Digging into residual injection variants, regex bypass, and the other workflows.Pipeline was masking exit codes — retesting docker/semgrep exit propagation directly.## Verdict

**BLOCKING — SAST fail-open on derived-output re-injection:** at `e61b96f`, re-introducing `TAG="${{ steps.meta.outputs.tag }}"` (the majority pre-fix shape) yields **0 findings / exit 0**. Upstream `run-shell-injection` only matches enumerated `github.*` / `inputs.*` contexts, not `steps.*.outputs.*`.

Injection hardening in `release-apk.yml` itself is complete (no residual reachable shell-injection variant found there).

---

## Findings

### 1. HIGH — SAST does not catch derived-output `${{ }}`→`run:` reintroduction  
**FILE:** `.semgrep/github-actions/run-shell-injection.yaml` (sole GHA injection rule at `e61b96f`; no `.semgrep/local/` yet)  
**MECHANISM:** Rule metavariable patterns enumerate direct untrusted `github.event.*` / `github.ref_name` / `inputs.*` only. They do **not** match `steps.*.outputs.*`.  
**SCENARIO (proved):** On the `e61b96f` ruleset + `semgrep/semgrep:1.90.0`:

| Planted pattern | Result |
|---|---|
| `TAG="${{ github.event.inputs.tag \|\| github.ref_name }}"` in `run:` | **finding, exit 1** |
| `TAG="${{ steps.meta.outputs.tag }}"` in `run:` (reverted Assert step) | **0 findings, exit 0** |

That second form was most of the pre-fix surface (meta/stage/verify/sha256/apk interpolations). A future incomplete fix that keeps env-style sources out of `run:` but re-pastes `${{ steps.meta.outputs.tag }}` / `${{ steps.stage.outputs.apk }}` into scripts is **not gated**. README claim that vendored `run-shell-injection` catches “the exact class” in `release-apk.yml` is overstated for derived outputs.

*(Note: branch HEAD `76cdc92` adds `.semgrep/local/no-run-block-interpolation.yaml` — outside this review range.)*

---

### 2. (No blocking injection residual) — `release-apk.yml` input→shell paths closed  
**FILE:** `.github/workflows/release-apk.yml`  
**Classification of every `${{ … }}` at `e61b96f`:**

| Location | Context | Class |
|---|---|---|
| L56 `ref:` | `with:` (checkout) | safe (action input, not shell) |
| L70–71 `TAG_INPUT`/`REF_NAME` | `env:` | safe |
| L96,136–137,157,186–187,202–206,236 | `env:` | safe |
| L110,125–129,156,201 | `env:` secrets/token | safe |
| L154,184,199,226,234 | `if:` | safe |
| L229–230 artifact `name`/`path` | `with:` | safe |
| **All `run:` bodies** | — | **zero `${{ }}`** |

Former run-bound values (`github.event.inputs.tag`, `github.ref_name`, `steps.meta.outputs.tag`, `steps.stage.outputs.{apk,sha256}`, `steps.signing.outputs.signed`, `steps.verify.outputs.cert_sha256`, `github.repository`) now enter via `env:` and are consumed as quoted `"$VAR"` / `"${VAR}"` in shell words. No unquoted `$VAR` word-split path remains on attacker-influenced data.

**Other workflows:** `ci.yml` / `link-check.yml` have no untrusted `${{ }}` inside `run:` (`link-check` only uses `github.ref` in `concurrency.group`). Release-apk was the vulnerable workflow for this class.

---

### 3. (OK) — Tag validation gates at entry before output emission  
**FILE:** `.github/workflows/release-apk.yml` L58–80  
**Order:** checkout (`with: ref`, not shell) → **first `run:`** resolves `TAG="${TAG_INPUT:-$REF_NAME}"`, regex-gates, **then** `echo "tag=$TAG" >> "$GITHUB_OUTPUT"`. No later step reads raw inputs; all use `steps.meta.outputs.tag`.

**Regex** `^v[0-9]+\.[0-9]+\.[0-9]+(-beta)?$` (bash `=~`):

- Accepts all 16 historical `v*` tags (`v0.6.0-beta` … `v0.9.1-beta`) and current `versionName` pairing (`0.9.1-beta` → `v0.9.1-beta`).
- Rejects `;`, `$(…)`, backticks, interior newlines + payload, spaces, CRLF, trailing newline (when bytes preserved).
- Anchored; no demonstrated injection bypass into later run blocks.

Checkout of a raw ref before validation is intentional and not a shell/derivation use; on validation failure later secret-using steps do not run.

---

### 4. (OK with residual) — SAST fail-closed for crash/config; pin + vendor OK; detection incomplete  
**FILE:** `.github/workflows/ci.yml` L134–145  

| Property | Status |
|---|---|
| `--error` | fails on findings (direct inject → exit 1) |
| `--strict` | empty/missing config → exit 7 |
| `run:` (not action) | non-zero (incl. crash) fails job; swallow of `semgrep-action@v1` gone |
| Image | `semgrep/semgrep:1.90.0` (tag-pinned, not digest) |
| Rules | `--config /src/.semgrep` vendored; no registry `auto` |
| Mount / pipe | `-v "$PWD:/src"`; no pipe masking exit |
| `run-shell-injection` present | yes — **direct** class only (see finding 1) |

No docker.sock; container runs vendor scanner on mounted source — acceptable.

---

### 5. LOW — Reject-path workflow-command injection via unvalidated tag in `::error::`  
**FILE:** `.github/workflows/release-apk.yml` L75–77  
**MECHANISM:** On regex failure, raw `$TAG` is echoed into `::error::…`. Newlines in the dispatch input can inject additional `::` workflow commands on the failed step’s stdout.  
**SCENARIO:** `workflow_dispatch` tag `evil\n::warning::injected` → validation fails, job exits 1; later steps (keystore) do not run. Blast radius limited; not signing-key RCE. Still a residual log/`::` channel on the reject path.

---

### 6. INFO — Behavior / scope  
- **No functional regression observed** in signed vs unsigned branches, cert pin, notes, artifact name, or conditionals; secrets stay step-scoped `env:` (recommended; masked).  
- **Mutable action tags** (`uses: …@vN`) deliberately omitted from the gate — **documented** in `.semgrep/README.md` (not silent); defensible separate supply-chain work. Same class residual: image tag `1.90.0` is not digest-pinned.  
- **License/provenance:** `.semgrep/README.md` records upstream commit `81634cfc…` and Semgrep Rules License v1.0.

---

## Summary table

| # | Sev | Topic | Blocking? |
|---|---|---|---|
| 1 | **HIGH** | SAST miss on `steps.*.outputs.*`→`run:` | **YES** (regression gate) |
| 2 | — | All release-apk shell injection paths closed | — |
| 3 | — | Tag gate at first run / before `GITHUB_OUTPUT` | — |
| 4 | — | Fail-closed container SAST; vendor+pin OK | — |
| 5 | LOW | `::error::` + raw reject tag | no |
| 6 | INFO | Mutable `@vN` omission documented; image not digest-pinned | no |

**One-line verdict:** **BLOCKING — at `e61b96f`, SAST does not fail a re-introduced `${{ steps.*.outputs.* }}` interpolation into `run:` (exit 0); release-apk injection paths themselves are closed.**
