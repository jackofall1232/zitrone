I'll independently verify the SAST run-interpolation gate at HEAD against the claimed multiline-span fix and remaining bypasses.Next I'll map the full SAST gate wiring and empirically test multiline-span and other `${{ }}`→`run:` variants.I'll run the pinned Semgrep image against crafted fixtures and the real tree.Building adversarial fixtures and running the pinned Semgrep gate against them.Parse errors on multiline fixtures need careful re-testing — including whether `--strict` fails the gate or silently skips.Confirming spans at the 10k boundary and re-running the pre-fix rule for a control comparison.Probing more exotic shape bypasses and verifying full-tree gate behavior.## 1. Multiline-span bypass CLOSED — **CONFIRMED-ACCURATE**

Pinned image `semgrep/semgrep:1.90.0@sha256:7b625711ba9b…`, rule = `git show HEAD:.semgrep/local/no-run-block-interpolation.yaml` (`generic_ellipsis_max_span: 10000`).

| blank lines inside `${{ … }}` in `run: \|` | pre-fix (262268c) | HEAD |
|---|---|---|
| 0, 5, 8, 9 | HIT | HIT |
| 10, 11, 40, 200, 500, 1000 | **MISS** (silent) | HIT |
| 9990 | — | HIT |
| 10001, 10010 | — | **MISS** |

Pre-fix threshold ~10 newlines (default ellipsis span). HEAD closes 5 / 40 / 200 / 1000. Residual miss only at **>10000** blank lines inside one `${{ }}` — not a reviewable/silent workflow; 10000 is a practical ceiling, not a real remaining gap under it.

## 2. Gate completeness — **CONFIRMED-ACCURATE**

Local rule HIT on: one-line `run:`, `run: |`, `run: >`, `run: |-`, quoted `"…"` / `'…'`, no-space `${{steps…}}`, `github.*`, `steps.*.outputs.*`, `inputs.*`, `matrix.*`, `env.*`, `needs.*`, `secrets.*`, `format`/`toJSON`/`fromJSON`, composite `action.yml`, flow-map / quoted-key / anchor, shell-before-run, working-directory-before-run.

Vendored `run-shell-injection` alone: **0** on `steps.*.outputs.*` (gap local closes). Combined: HIT local. Safe `env:` / `with:` / `if:` / `ref:`: **MISS** (no FP).

No reachable `${{ }}`→`run:` shape found that passes the combined gate under the 10000 ceiling.

## 3. No FP regression / gate intact — **CONFIRMED-ACCURATE**

- Full tree: `semgrep scan --config /src/.semgrep --error --strict` → **0 findings, exit 0** (22 rules load; local included).
- Planted `run: echo ${{ steps.x.outputs.y }}` → exit **1**, rule `zitrone-no-interpolation-in-run`.
- HEAD matcher is the working `${{ ... }}` + `options.generic_ellipsis_max_span: 10000` (not empty/broken). Worktree MD5 = committed blob.

## 4. release-apk.yml / digest pin — **CONFIRMED-ACCURATE**

- Zero `${{` inside any `run:` body; all untrusted/derived values via `env:` (`TAG_INPUT`/`REF_NAME`/`TAG`/`APK`/…).
- Tag validated first (`^v[0-9]+…`); `SAFE_TAG` via `tr -d '\r\n' | cut -c1-64` before `::error::`.
- CI still digest-pinned `semgrep/semgrep:1.90.0@sha256:7b625711…` with `--config /src/.semgrep --error --strict`. Delta touches only the local rule file.

---

**Verdict: CLEAN**
