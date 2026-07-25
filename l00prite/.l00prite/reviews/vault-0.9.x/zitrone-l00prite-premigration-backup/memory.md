# Zitrone — Project Memory

Durable project facts and decisions future agents should preserve. Vault-track detail also lives
in the rolling ledger `/root/l00prite/zitrone-vault-ledger.md` (behind that repo's .gitignore)
and in Claude auto-memory (`zitrone-*` files). This file is the in-repo durable summary.

## Decisions
- **Zero-knowledge server is non-negotiable.** The relay stores only opaque ciphertext and can
  prove no linkage. Deleting a ciphertext row is the shred; there is no key-escrow, no plaintext.
- **Box-role separation.** CX23 = production relay ONLY (deploy/restart happens there, by a
  human). CX33 (`ubuntu-4gb-hel1-3`, this box) = dev/build ONLY. Never deploy/restart the prod
  relay from CX33; server code changes are pushed, and a human redeploys on CX23.
- **Platform honesty hierarchy** (what each platform may claim): Android (reference, strongest)
  → Linux desktop → Web → iOS (trails). No platform claims a guarantee it hasn't shipped and
  verified. The website must not over-claim (see open hygiene item).
- **Deliver-then-claim.** State something is done only after it is built AND verified; report
  the actual command/exit-code evidence. Never claim a check that didn't run.
- **Phased PR + independent review discipline.** Security-sensitive work ships as small phased
  PRs, each independently reviewed before merge. For the hardest surface (D2c account-delete)
  that meant TWO blind reviewers (Codex + Grok) per round; each round caught different real
  defects. Lower-risk changes (D3) get one focused independent pass. Never self-re-read as the
  "final" review.
- **WRITER/READER invariant table first.** Before changing any durable multi-reader signal,
  enumerate every writer and what every reader assumes the signal MEANS. This is the direct
  countermeasure to the round-12→16 regression pattern (see `failures.md`).
- **Two-marker account-delete state machine.** `vault.delete-intent` (delete initiated — NEVER
  authorizes destroy) vs `vault.delete-confirmed` (server provably gone — the SOLE auto-destroy
  authorization). Token-clear in `onSessionRevoked` is guarded by
  `deleteInFlight || intentMarkerPresent()` (process flag OR durable disk marker).
- **A plain LOCK is not a DELETE.** `UnlockController.lock()` reseals current state (RETAINS auth
  on disk) then wipes RAM only; it writes NO delete marker and clears NO token. D3 auto-lock
  reuses this exact path, so it is not a new writer to the hardened delete/auth surface.
- **D3 auto-lock is device-level**, not vault-scoped (user decision): avoids a VaultState
  storage-format change, cleaner deniability (reveals nothing about vault count/active slot).
- **Reviewer-credit discipline.** Weekly credit limits: use review agents in moderation; cap any
  workflow at ~5 agents; prefer inline verification. Codex + Grok CLIs are on this box
  (`/root/.local/bin/codex`, `/root/.grok/bin/grok`, both authenticated); Grok reviews a local
  diff headlessly, Codex reviews from a pushed PR.

## Facts
- **main = `3c598ad`** (PR #46 / D2c merged): plausible-deniability slot-A live over the vault.
  D4 was absorbed into D2c.
- **D3 (idle auto-lock)**: branch `feat/0.9.1-vault-d3-autolock` @ `13d59cb`, **pushed**,
  **PR #48 OPEN**. Grok independent review DONE (verdict: not a new writer to delete/token
  state, 0 Critical/High/Med; 3 non-blocking Low). Codex review pending (user dispatches from
  the PR). NOT merged; no version bump. *(Note: an earlier task brief described D3 as "not yet
  pushed" — that is stale; it is pushed and PR #48 is open.)*
- **App version: vc16 / 0.9.0-beta** — unchanged, no bump yet.
- **Android signing:** release cert fingerprint `6c7f92a7…892753`; `keystore.properties` has 4
  fields, mode 600. Verify any release APK against this cert.
- **No NDK build path** for Android here; toolchain + signing details in Claude auto-memory
  `zitrone-build-env.md`.
- **Release gate for 0.9.1-beta + website flip** = PR-D (D2c✅ + D3 review→merge) + D5 + PR-F.
- Two parallel ledgers exist and are NOT yet reconciled: in-repo
  `.l00prite/ledger.md` (prior sessions, 0.7.5→0.8.1 era) and
  `/root/l00prite/zitrone-vault-ledger.md` (the 0.9.x D2c/D3 vault arc).

## Avoid
- Do not deploy/restart the production relay from CX33.
- Do not bump a version, push, or merge without explicit human approval.
- Do not move WHEN a durable signal is written without re-deriving what every reader assumes it
  MEANS (round-12 lesson — see `failures.md`).
- Do not store transient debugging notes here; keep this durable-only.
