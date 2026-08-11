# Zitrone — Project Memory

Durable project facts and decisions future agents should preserve. Deeper rolling review detail
for the vault arc also lives (LOCAL to the box, not in-repo) at
`/root/l00prite/zitrone-vault-ledger.md` and in Claude auto-memory (`zitrone-*` files). This
file is the in-repo durable summary.

## Decisions
- **Zero-knowledge server is non-negotiable.** The relay stores only opaque ciphertext and can
  prove no linkage. Deleting a ciphertext row is the shred; there is no key-escrow, no plaintext.
- **Box-role separation.** CX23 = production relay ONLY (deploy/restart happens there, by a
  human). CX33 (`ubuntu-4gb-hel1-3`, this box) = dev/build ONLY. Never deploy/restart the prod
  relay from CX33; server code changes are pushed, and a human redeploys on CX23.
- **Platform honesty hierarchy** (what each platform may claim): Android (reference, strongest)
  → Linux desktop → Web → iOS (trails). No platform claims a guarantee it hasn't shipped and
  verified. Linux and iOS are BACK-BURNER until after V1 Android testing (ruled; the libsignal
  interop plan in `research/plan.md` is research only, not a start signal).
- **Deliver-then-claim.** State something is done only after it is built AND verified; report
  the actual command/exit-code evidence. Never claim a check that didn't run.
- **Identity Watermarking, not "Invisible Watermarking".** The watermark is VISIBLE by design —
  maintainer decision (2026-07-30 rename, `0cf9ae0b`). Docs/website must not imply covert
  embedding.
- **Lemon-drop QR is a pointer, not a key.** A QR holder can read nothing; all copy must say so
  (`ce3a8827`).
- **Paired-blind independent review to clean convergence.** Security-sensitive work ships as
  small phased PRs; each is reviewed by TWO reviewers (Codex + Grok) blind to each other, and
  findings are adjudicated against source (never accepted on the reviewer's say-so). Clean
  convergence = both reviewers, no Critical/High/Medium, every finding verified. A single
  reviewer is empirically insufficient — across D2c and the 0.9.2 PR-1 arc each reviewer caught
  real defects the other missed, and each waved off the other's finding at least once. Fixes are
  NOT lower-risk than original code — re-review every fix delta the same way. Review the WHOLE
  unit, not only your delta (0.9.3 lesson). Never self-re-read as the "final" review.
- **WRITER/READER invariant table first.** Before changing any durable multi-reader signal,
  enumerate every writer and what every reader assumes the signal MEANS. Direct countermeasure to
  the round-12→16 regression pattern AND the PR-1 B1 marker defect (see `failures.md`).
- **Two-marker account-delete state machine.** `vault.delete-intent` (delete initiated — NEVER
  authorizes destroy) vs `vault.delete-confirmed` (server provably gone — the SOLE auto-destroy
  authorization). Token-clear in `onSessionRevoked` is guarded by
  `deleteInFlight || intentMarkerPresent()`.
- **A plain LOCK is not a DELETE.** `UnlockController.lock()` reseals current state (RETAINS auth
  on disk) then wipes RAM only; writes NO delete marker, clears NO token. D3 auto-lock reuses this
  exact path, so it is not a new writer to the hardened surface.
- **Fail-closed by default on protection reads.** Use proven-absent tristate (`Files.notExists`),
  not `File.exists()` (which conflates absent/indeterminate).
- **The onion mirror is a CODE allowlist.** Staging an APK server-side is never enough: bump
  `currentAPK` + `mirrorAssets` in `onion.go` AND redeploy. Filename and byte size are NOT build
  identity — verify sha256 + `aapt2 badging` (0.10.3 near-miss).
- **Release sequencing:** bump first, publish the release, THEN flip the website — combining bump
  and flip in one commit guarantees a transient `link-check` failure (0.10.3 lesson).
- **l00prite scaffolding is TRACKED in-repo** (2026-07-24 layout migration): the `l00prite/`
  folder (pointers, adapters, `.l00prite/` memory) is committed — nothing under it is gitignored,
  or the cross-session/cross-provider memory can't be discovered. See `constraints.md`.
- **Reviewer-credit discipline.** Weekly credit limits: use review agents in moderation; cap any
  workflow at ~5 agents; prefer inline verification. Codex + Grok CLIs are on this box
  (`/root/.local/bin/codex`, `/root/.grok/bin/grok`, both authenticated). Invocation that works:
  Grok `grok --cwd <repo> --permission-mode bypassPermissions --output-format plain --prompt-file
  <f>`; Codex `codex exec -C <repo> --sandbox read-only -` (prompt on stdin). Both run headless
  and review a local diff/commit range.

## Facts (current — 2026-08-11, synced to main @ `74157301`)
- **main = `74157301`.** App version **vc23 / 0.10.3-beta** — NO bump has happened since the
  0.10.3 release cut; CHANGELOG `[Unreleased]` is empty.
- **Shipped releases (all 2026-07):** 0.9.x vault/burn arc complete; **v0.10.0-beta** (vc21,
  decoy/cover traffic), **v0.10.1** (send-failure fixes, PR #64), **v0.10.2** (capacity/config —
  relay side, deployed to CX23 2026-07-30 at `755e558b`), **v0.10.3-beta** (vc23, attachment blob
  reclaim; APK sha256 `9c1ce6e9…b9c5c3`). Full detail per release in `ledger.md`.
- **0.11.0 polish round (FINAL ALPHA) — STARTED 2026-07-30, docs/website/honesty track ONLY**
  (14 commits `71553992`..`74157301`): `docs/FEATURES.md` added + audited twice (12 of 14 mockup
  claims verified; registration-PoW row removed as reversed), Identity Watermarking rename,
  website/README honesty fixes (two false watermark claims removed, Android-only reality stated,
  lemon-drop pointer-not-key, new /how-to walkthrough), Play-beta tester signup form, honest
  onboarding slides, screenshots pipeline. **No Android code polish yet.** The Android/code track
  is unscoped; planning inputs in Claude auto-memory (`zitrone-0110-planning-inputs`).
- **`research/plan.md`** = Linux desktop ↔ Android/iOS libsignal interop plan. RESEARCH ONLY, no
  code; Linux/iOS stay back-burner until after V1 Android testing.
- **⚠️ ONION MIRROR REDEPLOY OWED (CX23, human):** deployed relay `755e558b` names the unstaged
  0.10.2 APK, so the mirror serves no current APK (graceful "not staged"). Fix: redeploy at
  `main` ≥ `aa8876c7` + stage `zitrone-v0.10.3-beta.apk`
  (`9c1ce6e9e0bc64582e02faf10202198c837882a7ede55a83b2c25ace78b9c5c3`).
- **PR #60** (0.9.2 Unit W-A residue sweep) still OPEN, untouched since 2026-07-25 — needs a
  finish-or-close decision.
- **Standing pre-tester hygiene:** CI SAST silently broken (semgrep-action@v1 exits 0 on crash) +
  one real shell-injection ERROR in `release-apk.yml`; storage-format-stability decision owed;
  contact-deletion-permanence disclosure owed; 0.10.1 coordinator-harness scheduled debt.
- **Android signing:** release cert `6c7f92a7…892753` (unchanged since 0.10.0);
  `keystore.properties` (4 fields, mode 600). Verify any release APK against this cert. No NDK
  build path here; details in `zitrone-build-env.md` (Claude auto-memory).
- **Registration PoW is REVERSED** (`d83b9b3a`): clientKeyer is the answer; the `pow` package
  stays for dead drops only.
- **Two parallel ledgers still exist** (housekeeping): this in-repo `l00prite/.l00prite/ledger.md`
  and the local rolling `/root/l00prite/zitrone-vault-ledger.md` (deep review detail).

## Avoid
- Do not deploy/restart the production relay from CX33.
- Do not bump a version, push, or merge without explicit human approval.
- Do not gitignore anything under `l00prite/`.
- Do not move WHEN a durable signal is written without re-deriving what every reader assumes it
  MEANS, via the WRITER/READER table (round-12 + PR-1-B1 lesson — see `failures.md`).
- Do not treat filename or byte size as build identity — sha256 + badging only.
- Do not claim the onion mirror serves a release until code is redeployed AND the APK is staged.
- Do not start Linux/iOS work off the interop plan — it is research, gated behind V1 Android.
- Do not store transient debugging notes here; keep this durable-only.
