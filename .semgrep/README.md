<!--
  Zitrone — Copyright (C) 2026 Zitrone contributors
  Licensed under the GNU Affero General Public License v3.0 or later.
  SPDX-License-Identifier: AGPL-3.0-only
-->

# Vendored Semgrep rules — the SAST gate

These rules are the source of truth for the `Security scanning` job's Semgrep step
(`.github/workflows/ci.yml`). They are **vendored** (committed here), not fetched from the Semgrep
registry at CI time: the gate's behaviour must be a function of repo contents alone. A network fetch
is a silent-no-op failure point — exactly the class of bug this replaced (the previous
`semgrep/semgrep-action@v1` with `config: auto` exited 0 on its own crash / a registry-fetch failure,
so static analysis was silently green without running).

CI runs a **pinned** Semgrep container (`semgrep/semgrep:<version>` in `ci.yml`) with
`--config .semgrep --error --strict`:
- `--error` → non-zero exit when there are findings (gates the build on a real result).
- `--strict` → rule/parse/config problems are errors (non-zero), so a broken or empty ruleset can't
  masquerade as "0 findings".
- Any non-zero exit fails the `run:` step, so a Semgrep **crash** also fails the job.

## What's in the base (high-precision, gate-clean)
- **`github-actions/`** — Semgrep's official GitHub Actions **security** pack. `run-shell-injection`
  is the rule that catches `${{ … }}`-into-`run:` shell injection — the exact class that went
  uncaught in `release-apk.yml`. (Only rule deliberately omitted: `github-actions-mutable-action-tag`,
  which flags unpinned `uses: …@vN` action refs — a real but SEPARATE supply-chain hardening that
  means pinning every action to a 40-char SHA + SHA-pin maintenance; tracked as its own follow-up so
  the gate stays focused and green.)
- **`go/`** — Semgrep's official Go **language security** rules; clean against `server/`.

## Extending coverage (follow-up)
The full Kotlin / TypeScript / JavaScript packs are NOT gate-clean — they include informational /
audit rules that fire on correct code (e.g. `gcm-detection` flags the vault's legitimate AES-GCM
usage; the TypeScript pack alone is ~244 findings, mostly low-precision). Adding those languages
requires curating a high-precision subset that is clean against the tree, one language at a time —
each is its own vetted change, not a bulk import.

## Updating
- Bump the pinned Semgrep version in `ci.yml` deliberately (never a floating tag).
- After adding/updating rules, run locally against a clean tree and confirm 0 findings before
  committing, then confirm a planted finding fails (see the throwaway-branch proof in the PR).
